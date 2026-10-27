// Times the whole voice round trip through the REAL application: speech to text -> assistant -> text to speech.
//   node voice-lab/latency.mjs                         first recording in voice-lab/out/recordings/, 5 rounds
//   node voice-lab/latency.mjs path/to/take.wav --runs 3
//   APP_URL=http://localhost:3000 EMAIL=... PASSWORD=... node voice-lab/latency.mjs
// Needs the app running with voice switched on (VOICE_ENABLED=true, see docker-compose.voice.yml).
// It paces itself: the assistant endpoint allows 12 chat requests a minute per person, so a new round starts
// at most every 5.6 seconds (the same pacing as ai-eval/run-endpoint.mjs). No dependencies.
//
// ASSUMED request shapes (the voice endpoints were not written when this script was; adjust the two
// functions below if the backend differs):
//   POST /api/v1/voice/transcribe  multipart form with "audio" (the WAV) and "language" ("it"/"en"); answer { "text": "..." }
//   POST /api/v1/voice/speak       JSON { "text": "...", "language": "it" }; answer: audio bytes
//   Set TRANSCRIBE_BODY=raw to send the WAV bytes as the body instead (Content-Type: audio/wav, ?language=it).
import { existsSync, readdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { ensureDir, joinUrl, OUT_DIR, parseArgs } from "./lib/config.mjs";
import { fixed, formatTable } from "./lib/format.mjs";
import { max, median, min } from "./lib/stats.mjs";

const APP_URL = process.env.APP_URL ?? "http://localhost:3000";
// The demo owner of the Duodecim demo restaurant and the public demo password from README.md (a demo value, not a secret).
const EMAIL = process.env.EMAIL ?? "owner@duodecim.ristomanager.local";
const PASSWORD = process.env.PASSWORD ?? "Demo-Password-2026";
const MIN_GAP_BETWEEN_CHATS_MS = 5600; // 12 a minute is one every 5 s; a little spare
const TARGET_MS = 3000; // docs/VOICE_RESEARCH.md: under 3 seconds from end of speech to start of the spoken answer

const { flags, positional } = parseArgs(process.argv.slice(2));
const runs = flags.runs === undefined ? 5 : Number(flags.runs);
if (!Number.isInteger(runs) || runs < 1) { console.error("--runs must be a whole number of at least 1"); process.exit(1); }

// ---- the recording to send
const recordingsDir = join(OUT_DIR, "recordings");
let wavPath = positional[0];
if (!wavPath) {
  const first = existsSync(recordingsDir) ? readdirSync(recordingsDir).filter((f) => f.endsWith(".wav")).sort()[0] : null;
  if (!first) { console.error("No recording found. Give a WAV file as the first argument, or record phrases first (node voice-lab/serve.mjs, then /record.html)."); process.exit(1); }
  wavPath = join(recordingsDir, first);
}
if (!existsSync(wavPath)) { console.error(`File not found: ${wavPath}`); process.exit(1); }
const wavBytes = readFileSync(wavPath);
let language = /-en-/.test(wavPath) ? "en" : "it";
const metaPath = wavPath.replace(/\.wav$/i, ".json");
let referenceText = "";
if (existsSync(metaPath)) {
  try { const meta = JSON.parse(readFileSync(metaPath, "utf8")); language = meta.lang ?? language; referenceText = meta.text ?? ""; } catch { /* use the guess */ }
}

// ---- helpers
let token = null;
async function login() {
  const response = await fetch(joinUrl(APP_URL, "/api/v1/auth/login"), { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ email: EMAIL, password: PASSWORD }), signal: AbortSignal.timeout(30_000) });
  if (response.status !== 200) throw new Error(`Login as ${EMAIL} failed: HTTP ${response.status}. Is the app running at ${APP_URL}? (EMAIL and PASSWORD can be set as environment variables)`);
  token = (await response.json()).accessToken;
  if (!token) throw new Error("Login answered without an accessToken");
}

/** fetch with the bearer token; signs in again once on a 401. makeInit is called again for the retry. */
async function authed(path, makeInit) {
  const send = () => { const init = makeInit(); return fetch(joinUrl(APP_URL, path), { ...init, headers: { ...(init.headers ?? {}), Authorization: `Bearer ${token}` }, signal: AbortSignal.timeout(150_000) }); };
  let response = await send();
  if (response.status === 401) { await login(); response = await send(); }
  return response;
}

class VoiceUnavailable extends Error {}

function checkVoiceStatus(response, what) {
  if (response.status === 404) throw new VoiceUnavailable(`${what} answered 404: this version of the backend has no voice endpoints yet (or APP_URL points at something else). Nothing more to measure.`);
  if (response.status === 503) throw new VoiceUnavailable(`${what} answered 503: voice is switched off or the speech servers are not reachable. Start them (docker compose -f docker-compose.yml -f docker-compose.voice.yml --profile voice-gpu up -d) and make sure the backend has VOICE_ENABLED=true.`);
}

async function transcribeStage() {
  const started = performance.now();
  const response = await authed("/api/v1/voice/transcribe" + (process.env.TRANSCRIBE_BODY === "raw" ? `?language=${language}` : ""), () => {
    if (process.env.TRANSCRIBE_BODY === "raw") return { method: "POST", headers: { "Content-Type": "audio/wav" }, body: wavBytes };
    const form = new FormData();
    form.append("audio", new Blob([wavBytes], { type: "audio/wav" }), "take.wav");
    form.append("language", language);
    return { method: "POST", body: form };
  });
  checkVoiceStatus(response, "/api/v1/voice/transcribe");
  const bodyText = await response.text();
  const ms = performance.now() - started;
  if (!response.ok) throw new Error(`transcribe: HTTP ${response.status} ${bodyText.slice(0, 160)}`);
  const json = JSON.parse(bodyText);
  const text = String(json.text ?? json.transcript ?? "").trim();
  if (!text) throw new Error("transcribe: the answer had no text");
  return { text, ms };
}

async function chatStage(message) {
  const started = performance.now();
  const response = await authed("/api/v1/assistant/chat", () => ({ method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ message, history: [] }) }));
  const bodyText = await response.text();
  const ms = performance.now() - started;
  if (!response.ok) throw new Error(`chat: HTTP ${response.status} ${bodyText.slice(0, 160)}`);
  const reply = String(JSON.parse(bodyText).reply ?? "").trim();
  if (!reply) throw new Error("chat: the reply was empty");
  return { reply, ms };
}

async function speakStage(text) {
  const started = performance.now();
  const response = await authed("/api/v1/voice/speak", () => ({ method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ text, language }) }));
  checkVoiceStatus(response, "/api/v1/voice/speak");
  if (!response.ok) throw new Error(`speak: HTTP ${response.status} ${(await response.text()).slice(0, 160)}`);
  const reader = response.body.getReader();
  const chunks = [];
  let firstByteMs = null;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    if (value?.length) { if (firstByteMs === null) firstByteMs = performance.now() - started; chunks.push(value); }
  }
  const totalMs = performance.now() - started;
  if (firstByteMs === null) throw new Error("speak: the answer had no audio");
  return { audio: Buffer.concat(chunks), type: response.headers.get("content-type") ?? "", firstByteMs, totalMs };
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const extensionFor = (type) => (type.includes("mpeg") ? "mp3" : type.includes("wav") ? "wav" : type.includes("ogg") || type.includes("opus") ? "ogg" : type.includes("flac") ? "flac" : "bin");

// ---- the rounds
console.log(`App ${APP_URL}, recording ${wavPath} (${language}${referenceText ? `, "${referenceText}"` : ""}), ${runs} round(s)`);
console.log(`Pacing: at most one assistant request every ${MIN_GAP_BETWEEN_CHATS_MS / 1000} s (the server allows 12 a minute), so ${runs} rounds take at least ${Math.ceil(((runs - 1) * MIN_GAP_BETWEEN_CHATS_MS) / 1000)} s.\n`);

try {
  await login();
} catch (error) {
  console.error(error.message);
  process.exit(1);
}

const results = [];
let lastChatStart = 0;
for (let n = 1; n <= runs; n++) {
  const wait = lastChatStart + MIN_GAP_BETWEEN_CHATS_MS - Date.now();
  if (wait > 0) await sleep(wait); // before the round starts, so the pause is not counted in its timings
  try {
    const stt = await transcribeStage();
    lastChatStart = Date.now();
    const chat = await chatStage(stt.text);
    const speak = await speakStage(chat.reply);
    const row = { stt: stt.ms, chat: chat.ms, firstByte: speak.firstByteMs, speak: speak.totalMs, firstAudio: stt.ms + chat.ms + speak.firstByteMs, total: stt.ms + chat.ms + speak.totalMs };
    results.push(row);
    console.log(`Round ${n}: heard "${stt.text}" -> "${chat.reply.slice(0, 70).replace(/\s+/g, " ")}${chat.reply.length > 70 ? "..." : ""}"`);
    console.log(`         listen ${Math.round(row.stt)} ms, assistant ${Math.round(row.chat)} ms, speech first byte ${Math.round(row.firstByte)} ms (whole audio ${Math.round(row.speak)} ms)  =>  first sound after ${Math.round(row.firstAudio)} ms`);
    const file = join(ensureDir(join(OUT_DIR, "latency")), `last-reply.${extensionFor(speak.type)}`);
    writeFileSync(file, speak.audio);
  } catch (error) {
    if (error instanceof VoiceUnavailable) { console.error(`\n${error.message}`); process.exit(1); }
    lastChatStart = Math.max(lastChatStart, Date.now()); // a failed round may still have used a chat request
    console.log(`Round ${n}: FAILED ${error.message}`);
  }
}

if (results.length === 0) { console.error("\nEvery round failed, so there is nothing to summarise."); process.exit(1); }
const stage = (name, key) => [name, fixed(min(results.map((r) => r[key])), 0), fixed(median(results.map((r) => r[key])), 0), fixed(max(results.map((r) => r[key])), 0)];
console.log(`\n${results.length} of ${runs} rounds worked. Times in milliseconds:`);
console.log(formatTable(["stage", "min", "median", "max"], [
  stage("1 speech to text (upload + engine)", "stt"),
  stage("2 assistant (language model)", "chat"),
  stage("3 text to speech: first audio byte", "firstByte"),
  stage("3 text to speech: whole audio", "speak"),
  stage("TIME UNTIL FIRST SOUND (1+2+3 first byte)", "firstAudio"),
  stage("TOTAL until the audio has arrived", "total"),
], { rightAlign: [1, 2, 3] }));
const firstSound = median(results.map((r) => r.firstAudio));
console.log(`\nMedian time until first sound: ${Math.round(firstSound)} ms. Target in docs/VOICE_RESEARCH.md: under ${TARGET_MS} ms ("${firstSound <= TARGET_MS ? "inside" : "OUTSIDE"} the target").`);
console.log("The time is measured from the moment the upload starts, so it leaves out the time you spend speaking. The last spoken reply is saved in voice-lab/out/latency/ so you can listen to it.");
