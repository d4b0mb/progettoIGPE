// Turns the exported ratings.json (blind labels plus the hidden mapping) and out/tts/results.json (timings)
// into one row per candidate, and one per candidate and language. Pure functions.
import { mean } from "./stats.mjs";

const isScore = (v) => Number.isFinite(v) && v >= 1 && v <= 5;

/**
 * ratingsDoc: { mapping: { <phraseId>: { A: "<candidate>", ... } }, ratings: [{ phraseId, lang, label, natural, trust, comment }] }
 * ttsResults: the "results" array of out/tts/results.json: [{ candidate, lang, ttfbMs, totalMs, error? }]
 * Returns rows { candidate, lang ("all", "it" or "en"), nNatural, natural, nTrust, trust, comments, calls, ttfbMs, totalMs }
 * sorted by candidate, then "all", "en", "it". Means are null when nothing was rated or timed.
 */
export function aggregateRatings(ratingsDoc, ttsResults = []) {
  const mapping = ratingsDoc?.mapping ?? {};
  const groups = new Map(); // "candidate|lang" -> { natural: [], trust: [], comments: 0 }
  const bucket = (candidate, lang) => {
    const k = `${candidate}|${lang}`;
    if (!groups.has(k)) groups.set(k, { candidate, lang, natural: [], trust: [], comments: 0 });
    return groups.get(k);
  };

  for (const rating of ratingsDoc?.ratings ?? []) {
    const candidate = mapping[rating.phraseId]?.[rating.label];
    if (!candidate) continue; // a label that cannot be unblinded is ignored
    for (const lang of ["all", rating.lang]) {
      const g = bucket(candidate, lang);
      if (isScore(rating.natural)) g.natural.push(rating.natural);
      if (isScore(rating.trust)) g.trust.push(rating.trust);
      if (typeof rating.comment === "string" && rating.comment.trim()) g.comments++;
    }
  }
  for (const result of ttsResults) for (const lang of ["all", result.lang]) bucket(result.candidate, lang); // candidates with timings but no ratings still appear

  const langOrder = { all: 0, en: 1, it: 2 };
  return [...groups.values()]
    .map((g) => {
      const timed = ttsResults.filter((r) => r.candidate === g.candidate && (g.lang === "all" || r.lang === g.lang));
      const good = timed.filter((r) => !r.error && Number.isFinite(r.ttfbMs));
      return {
        candidate: g.candidate,
        lang: g.lang,
        nNatural: g.natural.length,
        natural: mean(g.natural),
        nTrust: g.trust.length,
        trust: mean(g.trust),
        comments: g.comments,
        calls: timed.length,
        ttfbMs: mean(good.map((r) => r.ttfbMs)),
        totalMs: mean(good.map((r) => r.totalMs)),
      };
    })
    .sort((a, b) => a.candidate.localeCompare(b.candidate) || (langOrder[a.lang] ?? 9) - (langOrder[b.lang] ?? 9));
}
