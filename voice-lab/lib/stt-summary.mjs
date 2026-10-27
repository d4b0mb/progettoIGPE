// Averages of the speech-to-text results. Pure functions.
import { mean, median, percentile } from "./stats.mjs";

/**
 * rows: [{ candidate, lang, noiseNote, wer, cer, ms, error? }]. Rows with an error are not averaged (they are counted).
 * groupBy: "candidate" | "lang" | "noiseNote" ; the result has one entry per candidate and group value:
 * { candidate, group, n, errors, wer, cer, medianMs, p90Ms }. The WER and CER are plain means of the per-phrase rates.
 */
export function summarizeStt(rows, groupBy = "candidate") {
  const groups = new Map();
  for (const row of rows) {
    const group = groupBy === "candidate" ? "all" : (row[groupBy] ?? "-");
    const k = `${row.candidate}\u0000${group}`;
    if (!groups.has(k)) groups.set(k, { candidate: row.candidate, group, rows: [] });
    groups.get(k).rows.push(row);
  }
  return [...groups.values()]
    .map(({ candidate, group, rows: mine }) => {
      const good = mine.filter((r) => !r.error);
      return {
        candidate,
        group,
        n: good.length,
        errors: mine.length - good.length,
        wer: mean(good.map((r) => r.wer)),
        cer: mean(good.map((r) => r.cer)),
        medianMs: median(good.map((r) => r.ms)),
        p90Ms: percentile(good.map((r) => r.ms), 90),
      };
    })
    .sort((a, b) => a.candidate.localeCompare(b.candidate) || String(a.group).localeCompare(String(b.group)));
}
