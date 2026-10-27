// Unblinds the listening ratings and prints one table per candidate.
//   node voice-lab/score.mjs                      reads voice-lab/out/ratings/ratings.json
//   node voice-lab/score.mjs path/to/ratings.json (the file downloaded by "Export ratings")
//   node voice-lab/score.mjs --comments           also prints the comments you wrote, unblinded
// Timings come from voice-lab/out/tts/results.json. No dependencies.
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { OUT_DIR, parseArgs } from "./lib/config.mjs";
import { fixed, formatTable } from "./lib/format.mjs";
import { aggregateRatings } from "./lib/ratings.mjs";

const { flags, positional } = parseArgs(process.argv.slice(2), { booleans: ["comments"] });
const ratingsPath = positional[0] ?? join(OUT_DIR, "ratings", "ratings.json");
if (!existsSync(ratingsPath)) {
  console.error(`No ratings file at ${ratingsPath}.
Open the listening page, press "Export ratings", then either move the downloaded ratings.json to voice-lab/out/ratings/
or pass its path:  node voice-lab/score.mjs C:\\path\\to\\ratings.json
(If the page was opened through node voice-lab/serve.mjs, the export is saved there automatically.)`);
  process.exit(1);
}
const doc = JSON.parse(readFileSync(ratingsPath, "utf8"));

let timings = [];
const resultsPath = join(OUT_DIR, "tts", "results.json");
if (existsSync(resultsPath)) timings = JSON.parse(readFileSync(resultsPath, "utf8")).results ?? [];
else console.log("(no out/tts/results.json found: timing columns will be empty)\n");

const rows = aggregateRatings(doc, timings);
const headers = ["candidate", "lang", "rated", "sounds like a person (1-5)", "trust for allergy warning (1-5)", "mean first byte (ms)", "mean total (ms)"];
const table = (subset) => formatTable(headers, subset.map((r) => [r.candidate, r.lang, Math.max(r.nNatural, r.nTrust), fixed(r.natural, 2), fixed(r.trust, 2), fixed(r.ttfbMs, 0), fixed(r.totalMs, 0)]), { rightAlign: [2, 3, 4, 5, 6] });

console.log("All languages together\n" + table(rows.filter((r) => r.lang === "all")));
console.log("\nBy language\n" + table(rows.filter((r) => r.lang !== "all")));
console.log("\n'rated' counts the phrases you gave a score to. 5 is best. Compare the trust column most carefully: it is the one that matters for allergy warnings.");

if (flags.comments) {
  console.log("\nComments");
  for (const rating of doc.ratings ?? []) {
    const candidate = doc.mapping?.[rating.phraseId]?.[rating.label];
    if (candidate && rating.comment?.trim()) console.log(`  ${candidate}  ${rating.phraseId}  (${rating.natural ?? "-"}/${rating.trust ?? "-"})  ${rating.comment.trim()}`);
  }
}
