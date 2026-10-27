// Speaks every "tts" phrase of phrases.json with every text-to-speech candidate of candidates.json,
// saves the audio, times it, and builds the blind listening page.
//   node voice-lab/tts-compare.mjs
//   node voice-lab/tts-compare.mjs --only kokoro-it-sara --lang it --limit 3
// Output: voice-lab/out/tts/<candidate>/<id>.<ext>, voice-lab/out/tts/results.json, voice-lab/out/listen.html
// Needs the speech servers running (see candidates.json). No dependencies.
import { readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { ensureDir, extensionForFormat, joinUrl, LAB_DIR, loadCandidates, loadPhrases, OUT_DIR, parseArgs, safeName } from "./lib/config.mjs";
import { buildListenData, renderListenHtml } from "./lib/listen-page.mjs";
import { fixed, formatTable } from "./lib/format.mjs";
import { mean } from "./lib/stats.mjs";

const { flags } = parseArgs(process.argv.slice(2));
const only = typeof flags.only === "string" ? flags.only : null;
const lang = typeof flags.lang === "string" ? flags.lang : null;
const limit = flags.limit === undefined ? Infinity : Number(flags.limit);
if (lang && !["it", "en"].includes(lang)) { console.error('--lang must be "it" or "en"'); process.exit(1); }
if (!(limit > 0)) { console.error("--limit must be a positive number"); process.exit(1); }

const allPhrases = loadPhrases().tts;
const phrases = allPhrases.filter((p) => !lang || p.lang === lang).slice(0, limit);
let candidates = loadCandidates("tts");
if (only) candidates = candidates.filter((c) => c.name === only);
if (candidates.length === 0) { console.error(`No text-to-speech candidate named "${only}" in voice-lab/candidates.json`); process.exit(1); }

const TTS_DIR = join(OUT_DIR, "tts");
const RESULTS_PATH = join(TTS_DIR, "results.json");

/** One call to {baseUrl}/v1/audio/speech. Returns the audio bytes and the two timings. */
async function synthesize(candidate, text, voice, format) {
  const started = performance.now();
  const response = await fetch(joinUrl(candidate.baseUrl, "/v1/audio/speech"), {
    method: "POST",
    headers: { "Content-Type": "application/json", ...(candidate.headers ?? {}) },
    body: JSON.stringify({ model: candidate.model, input: text, voice, response_format: format }),
    signal: AbortSignal.timeout(120_000),
  });
  if (!response.ok) throw new Error(`HTTP ${response.status} ${(await response.text().catch(() => "")).slice(0, 200)}`.trim());
  if ((response.headers.get("content-type") ?? "").includes("json")) throw new Error(`Got JSON instead of audio: ${(await response.text()).slice(0, 200)}`);
  const reader = response.body.getReader();
  const chunks = [];
  let ttfbMs = null;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    if (value?.length) {
      if (ttfbMs === null) ttfbMs = performance.now() - started; // first bytes of audio arrived
      chunks.push(value);
    }
  }
  const totalMs = performance.now() - started;
  const audio = Buffer.concat(chunks);
  if (audio.length === 0) throw new Error("The server answered with an empty body");
  return { audio, ttfbMs: ttfbMs ?? totalMs, totalMs };
}

const fresh = [];
for (const candidate of candidates) {
  const format = candidate.format ?? "mp3";
  const extension = extensionForFormat(format);
  ensureDir(join(TTS_DIR, safeName(candidate.name)));
  console.log(`\n${candidate.name}  (${candidate.baseUrl}, model ${candidate.model}, ${format})`);

  // A throwaway first call so that loading the model does not count against the first phrase.
  const warmVoice = Object.values(candidate.voices)[0];
  await synthesize(candidate, "Ok.", warmVoice, format).catch((error) => console.log(`  warm-up call failed (${error.message}); continuing`));

  for (const phrase of phrases) {
    const voice = candidate.voices[phrase.lang];
    if (!voice) { console.log(`  skip ${phrase.id}: no voice for "${phrase.lang}" in candidates.json`); continue; }
    const entry = { candidate: candidate.name, id: phrase.id, lang: phrase.lang, text: phrase.text, voice, format, file: null, bytes: 0, ttfbMs: null, totalMs: null, takenAt: new Date().toISOString() };
    try {
      const { audio, ttfbMs, totalMs } = await synthesize(candidate, phrase.text, voice, format);
      const relative = `tts/${safeName(candidate.name)}/${phrase.id}.${extension}`;
      writeFileSync(join(OUT_DIR, ...relative.split("/")), audio);
      Object.assign(entry, { file: relative, bytes: audio.length, ttfbMs: Math.round(ttfbMs), totalMs: Math.round(totalMs) });
      console.log(`  ok   ${phrase.id}  first byte ${String(entry.ttfbMs).padStart(5)} ms  total ${String(entry.totalMs).padStart(5)} ms  ${(audio.length / 1024).toFixed(1)} KB`);
    } catch (error) {
      entry.error = String(error.message ?? error);
      console.log(`  FAIL ${phrase.id}  ${entry.error}`);
    }
    fresh.push(entry);
  }
}

// Merge with an earlier run, so --only or --limit does not throw away the other results.
let previous = [];
try { previous = JSON.parse(readFileSync(RESULTS_PATH, "utf8")).results ?? []; } catch { /* first run */ }
const key = (r) => `${r.candidate}|${r.id}`;
const replaced = new Set(fresh.map(key));
const order = new Map(allPhrases.map((p, i) => [p.id, i]));
const results = [...previous.filter((r) => !replaced.has(key(r))), ...fresh].sort((a, b) => a.candidate.localeCompare(b.candidate) || (order.get(a.id) ?? 0) - (order.get(b.id) ?? 0));
ensureDir(TTS_DIR);
writeFileSync(RESULTS_PATH, JSON.stringify({ generatedAt: new Date().toISOString(), results }, null, 2));

const rows = [...new Set(fresh.map((r) => r.candidate))].map((name) => {
  const mine = fresh.filter((r) => r.candidate === name);
  const good = mine.filter((r) => !r.error);
  return [name, good.length, mine.length - good.length, fixed(mean(good.map((r) => r.ttfbMs)), 0), fixed(mean(good.map((r) => r.totalMs)), 0)];
});
console.log("\nThis run:\n" + formatTable(["candidate", "ok", "failed", "mean first byte (ms)", "mean total (ms)"], rows, { rightAlign: [1, 2, 3, 4] }));
console.log(`\nSaved ${RESULTS_PATH}`);

const listen = buildListenData(allPhrases, results);
if (listen.items.length === 0) {
  console.log("No audio was produced, so listen.html was not built. Check the errors above and that the servers are running.");
  process.exit(1);
}
const page = renderListenHtml(listen, readFileSync(join(LAB_DIR, "web", "listen-client.js"), "utf8"));
const pagePath = join(OUT_DIR, "listen.html");
writeFileSync(pagePath, page);
console.log(`Built ${pagePath} (${listen.items.length} phrases)`);
console.log("Listen with:  node voice-lab/serve.mjs   then open http://127.0.0.1:8099/out/listen.html");
