import { test } from "node:test";
import assert from "node:assert/strict";
import { aggregateRatings } from "../lib/ratings.mjs";
import { summarizeStt } from "../lib/stt-summary.mjs";

const close = (actual, expected) => assert.ok(Math.abs(actual - expected) < 1e-9, `${actual} is not close to ${expected}`);

const doc = {
  mapping: { p1: { A: "c1", B: "c2" }, p2: { A: "c2", B: "c1" } },
  ratings: [
    { phraseId: "p1", lang: "it", label: "A", natural: 4, trust: 5, comment: "" },
    { phraseId: "p1", lang: "it", label: "B", natural: 2, trust: 3, comment: "flat" },
    { phraseId: "p2", lang: "en", label: "A", natural: 3, trust: null, comment: "  " },
    { phraseId: "p2", lang: "en", label: "B", natural: 5, trust: 4, comment: "" },
    { phraseId: "p9", lang: "en", label: "A", natural: 1, trust: 1, comment: "unknown phrase" }, // cannot be unblinded
    { phraseId: "p1", lang: "it", label: "B", natural: 9, trust: 0, comment: "" }, // out of range, ignored
  ],
};
const timings = [
  { candidate: "c1", lang: "it", ttfbMs: 100, totalMs: 300 },
  { candidate: "c1", lang: "en", ttfbMs: 200, totalMs: 500 },
  { candidate: "c1", lang: "it", ttfbMs: null, totalMs: null, error: "HTTP 500" },
  { candidate: "c2", lang: "it", ttfbMs: 50, totalMs: 150 },
];

test("aggregateRatings unblinds through the mapping and averages per candidate", () => {
  const rows = aggregateRatings(doc, timings);
  const c1 = rows.find((r) => r.candidate === "c1" && r.lang === "all");
  assert.equal(c1.nNatural, 2);
  close(c1.natural, 4.5); // 4 and 5
  close(c1.trust, 4.5); // 5 and 4
  const c2 = rows.find((r) => r.candidate === "c2" && r.lang === "all");
  assert.equal(c2.nNatural, 2); // 2 and 3; the 9 is ignored
  close(c2.natural, 2.5);
  assert.equal(c2.nTrust, 1);
  close(c2.trust, 3);
  assert.equal(c2.comments, 1); // "flat"; a blank comment does not count
});

test("aggregateRatings splits by language and adds the timings", () => {
  const rows = aggregateRatings(doc, timings);
  const c1it = rows.find((r) => r.candidate === "c1" && r.lang === "it");
  close(c1it.natural, 4);
  assert.equal(c1it.calls, 2);
  close(c1it.ttfbMs, 100); // the failed call is not averaged
  const c1all = rows.find((r) => r.candidate === "c1" && r.lang === "all");
  assert.equal(c1all.calls, 3);
  close(c1all.ttfbMs, 150);
  close(c1all.totalMs, 400);
});

test("aggregateRatings sorts by candidate, then all, en, it", () => {
  const rows = aggregateRatings(doc, timings);
  assert.deepEqual(rows.map((r) => `${r.candidate}:${r.lang}`), ["c1:all", "c1:en", "c1:it", "c2:all", "c2:en", "c2:it"]);
});

test("a candidate with timings but no ratings still appears, with empty means", () => {
  const rows = aggregateRatings({ mapping: {}, ratings: [] }, [{ candidate: "c9", lang: "it", ttfbMs: 10, totalMs: 20 }]);
  const row = rows.find((r) => r.candidate === "c9" && r.lang === "all");
  assert.equal(row.natural, null);
  assert.equal(row.nNatural, 0);
  close(row.ttfbMs, 10);
});

const sttRows = [
  { candidate: "c1", lang: "it", noiseNote: "quiet room", wer: 0.2, cer: 0.1, ms: 100 },
  { candidate: "c1", lang: "it", noiseNote: "quiet room", wer: 0, cer: 0, ms: 300 },
  { candidate: "c1", lang: "en", noiseNote: "music", wer: 1, cer: 0.5, ms: 200 },
  { candidate: "c1", lang: "en", noiseNote: "music", wer: null, cer: null, ms: null, error: "HTTP 500" },
  { candidate: "c2", lang: "it", noiseNote: "quiet room", wer: 0.5, cer: 0.25, ms: 50 },
];

test("summarizeStt per candidate: means of good rows, errors counted", () => {
  const [c1, c2] = summarizeStt(sttRows, "candidate");
  assert.equal(c1.group, "all");
  assert.equal(c1.n, 3);
  assert.equal(c1.errors, 1);
  close(c1.wer, 0.4);
  close(c1.cer, 0.2);
  close(c1.medianMs, 200);
  assert.equal(c2.n, 1);
  close(c2.wer, 0.5);
});

test("summarizeStt per language and per noise note", () => {
  const byLang = summarizeStt(sttRows, "lang");
  assert.deepEqual(byLang.map((s) => `${s.candidate}:${s.group}:${s.n}:${s.errors}`), ["c1:en:1:1", "c1:it:2:0", "c2:it:1:0"]);
  close(byLang[1].wer, 0.1);
  const byNoise = summarizeStt(sttRows, "noiseNote");
  assert.deepEqual(byNoise.map((s) => `${s.candidate}:${s.group}`), ["c1:music", "c1:quiet room", "c2:quiet room"]);
});

test("summarizeStt on no rows", () => {
  assert.deepEqual(summarizeStt([], "candidate"), []);
});
