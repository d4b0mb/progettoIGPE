// Sends every recording in voice-lab/out/recordings/ to every speech-to-text candidate and scores the answer.
//   node voice-lab/stt-eval.mjs
//   node voice-lab/stt-eval.mjs --only whisper-turbo --lang it --limit 10
// Needs: recordings made with the recorder page (<id>.wav and <id>.json), and the engines running.
// Output: a table on screen, voice-lab/out/stt/results.csv (one row per phrase and candidate) and
// voice-lab/out/stt/summary.csv (the averages). Each run replaces both files. No dependencies.
import { existsSync, readdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { ensureDir, joinUrl, loadCandidates, loadPhrases, OUT_DIR, parseArgs } from "./lib/config.mjs";
import { fixed, formatTable, percent, toCsv } from "./lib/format.mjs";
import { summarizeStt } from "./lib/stt-summary.mjs";
import { charErrorRate, wordErrorRate } from "./lib/text.mjs";

const { flags } = parseArgs(process.argv.slice(2));
const only = typeof flags.only === "string" ? flags.only : null;
const lang = typeof flags.lang === "string" ? flags.lang : null;
const limit = flags.limit === undefined ? Infinity : Number(flags.limit);
if (lang && !["it", "en"].includes(lang)) { console.error('--lang must be "it" or "en"'); process.exit(1); }
if (!(limit > 0)) { console.error("--limit must be a positive number"); process.exit(1); }

let candidates = loadCandidates("stt");
if (only) candidates = candidates.filter((c) => c.name === only);
if (candidates.length === 0) { console.error(`No speech-to-text candidate named "${only}" in voice-lab/candidates.json`); process.exit(1); }

// The recordings: every <id>.wav that has an <id>.json next to it, in the order of phrases.json.
const recordingsDir = join(OUT_DIR, "recordings");
if (!existsSync(recordingsDir)) {
  console.error("There is no voice-lab/out/recordings/ folder yet. Record the phrases first: node voice-lab/serve.mjs, then open http://127.0.0.1:8099/record.html");
  process.exit(1);
}
const order = new Map(loadPhrases().stt.map((p, i) => [p.id, i]));
const recordings = [];
for (const file of readdirSync(recordingsDir).filter((f) => f.endsWith(".wav"))) {
  const id = file.slice(0, -4);
  const metaPath = join(recordingsDir, `${id}.json`);
  if (!existsSync(metaPath)) { console.log(`skip ${file}: no ${id}.json next to it`); continue; }
  const meta = JSON.parse(readFileSync(metaPath, "utf8"));
  if (lang && meta.lang !== lang) continue;
  recordings.push({ id, wavPath: join(recordingsDir, file), lang: meta.lang, text: meta.text, noiseNote: meta.noiseNote ?? "", durationMs: meta.durationMs });
}
recordings.sort((a, b) => (order.get(a.id) ?? 999) - (order.get(b.id) ?? 999) || a.id.localeCompare(b.id));
recordings.splice(Math.min(limit, recordings.length));
if (recordings.length === 0) { console.error("No recordings found (a .wav and its .json) in voice-lab/out/recordings/."); process.exit(1); }

/** One call to {baseUrl}/v1/audio/transcriptions. */
async function transcribe(candidate, recording, bytes) {
  const form = new FormData();
  form.append("file", new Blob([bytes], { type: "audio/wav" }), `${recording.id}.wav`);
  form.append("model", candidate.model);
  form.append("language", recording.lang);
  form.append("response_format", "json");
  const started = performance.now();
  const response = await fetch(joinUrl(candidate.baseUrl, "/v1/audio/transcriptions"), { method: "POST", headers: candidate.headers ?? {}, body: form, signal: AbortSignal.timeout(120_000) });
  const body = await response.text();
  const ms = Math.round(performance.now() - started);
  if (!response.ok) throw new Error(`HTTP ${response.status} ${body.slice(0, 200)}`.trim());
  let json;
  try { json = JSON.parse(body); } catch { throw new Error(`Answer is not JSON: ${body.slice(0, 120)}`); }
  if (typeof json.text !== "string") throw new Error(`Answer has no "text": ${body.slice(0, 120)}`);
  return { text: json.text.trim(), ms };
}

console.log(`${recordings.length} recordings, ${candidates.length} candidate(s)`);
const rows = [];
for (const candidate of candidates) {
  console.log(`\n${candidate.name}  (${candidate.baseUrl}, model ${candidate.model})`);
  // A throwaway first call so that loading the model does not count against the first phrase.
  await transcribe(candidate, recordings[0], readFileSync(recordings[0].wavPath)).catch((error) => console.log(`  warm-up call failed (${error.message}); continuing`));
  for (const recording of recordings) {
    const row = { candidate: candidate.name, id: recording.id, lang: recording.lang, noiseNote: recording.noiseNote, reference: recording.text, hypothesis: "", wer: null, cer: null, ms: null, error: "" };
    try {
      const { text, ms } = await transcribe(candidate, recording, readFileSync(recording.wavPath));
      Object.assign(row, { hypothesis: text, ms, wer: wordErrorRate(recording.text, text, recording.lang), cer: charErrorRate(recording.text, text, recording.lang) });
      console.log(`  ${recording.id}  WER ${percent(row.wer).padStart(6)}  ${String(ms).padStart(5)} ms  ${text}${row.wer > 0 ? `\n      wanted: ${recording.text}` : ""}`);
    } catch (error) {
      row.error = String(error.message ?? error);
      console.log(`  ${recording.id}  FAIL ${row.error}`);
    }
    rows.push(row);
  }
}

// ---- tables
const summaryRows = [];
const printTable = (title, summary, groupName) => {
  console.log(`\n${title}\n` + formatTable(["candidate", groupName, "phrases", "errors", "WER", "CER", "median ms", "p90 ms"], summary.map((s) => [s.candidate, s.group, s.n, s.errors, percent(s.wer), percent(s.cer), fixed(s.medianMs, 0), fixed(s.p90Ms, 0)]), { rightAlign: [2, 3, 4, 5, 6, 7] }));
  for (const s of summary) summaryRows.push([title, s.candidate, s.group, s.n, s.errors, s.wer, s.cer, s.medianMs, s.p90Ms]);
};
printTable("Per candidate", summarizeStt(rows, "candidate"), "group");
printTable("Per candidate and language", summarizeStt(rows, "lang"), "lang");
printTable("Per candidate and noise note", summarizeStt(rows, "noiseNote"), "noise note");
console.log("\nWER = word error rate, CER = character error rate (lower is better; 0% is perfect). Texts are normalised first (case, accents, punctuation, numbers as words): this is approximate, see voice-lab/README.md.");

// ---- files. A byte order mark at the start makes Excel show accents correctly.
const sttDir = ensureDir(join(OUT_DIR, "stt"));
const csvRows = rows.map((r) => [r.candidate, r.id, r.lang, r.noiseNote, r.reference, r.hypothesis, r.wer === null ? "" : r.wer.toFixed(4), r.cer === null ? "" : r.cer.toFixed(4), r.ms ?? "", r.error]);
writeFileSync(join(sttDir, "results.csv"), "﻿" + toCsv(["candidate", "id", "lang", "noise_note", "reference", "hypothesis", "wer", "cer", "ms", "error"], csvRows));
const summaryCsv = summaryRows.map(([view, candidate, group, n, errors, wer, cer, medianMs, p90Ms]) => [view, candidate, group, n, errors, wer === null ? "" : wer.toFixed(4), cer === null ? "" : cer.toFixed(4), medianMs === null ? "" : Math.round(medianMs), p90Ms === null ? "" : Math.round(p90Ms)]);
writeFileSync(join(sttDir, "summary.csv"), "﻿" + toCsv(["view", "candidate", "group", "phrases", "errors", "wer", "cer", "median_ms", "p90_ms"], summaryCsv));
console.log(`\nSaved ${join(sttDir, "results.csv")} and summary.csv`);
