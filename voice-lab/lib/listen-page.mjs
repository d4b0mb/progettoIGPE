// Builds the blind listening test: which voices are heard for each phrase, under which letter, and the
// single self-contained HTML page. Pure functions (the caller reads web/listen-client.js and writes the file).

/** 32-bit FNV-1a hash of a string, as an unsigned number. */
export function hash32(text) {
  let h = 0x811c9dc5;
  for (let i = 0; i < text.length; i++) {
    h ^= text.charCodeAt(i);
    h = Math.imul(h, 0x01000193) >>> 0;
  }
  return h >>> 0;
}

/** A small seeded random number generator (mulberry32): the same seed always gives the same numbers in 0..1. */
export function seededRandom(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/** Fisher-Yates shuffle of a copy of the list, driven by the given random function. */
export function shuffled(list, random) {
  const copy = [...list];
  for (let i = copy.length - 1; i > 0; i--) {
    const j = Math.floor(random() * (i + 1));
    [copy[i], copy[j]] = [copy[j], copy[i]];
  }
  return copy;
}

/**
 * The listening test data. phrases: the "tts" phrases. results: entries of out/tts/results.json, each
 * { candidate, id, file, error? } where file is the audio path relative to voice-lab/out/ ("tts/<candidate>/<id>.mp3").
 * For each phrase the successful candidates are put in a random but repeatable order (seeded by the phrase and the
 * candidate names, so rebuilding the page with the same audio gives the same letters) and named A, B, C, D.
 * At most maxPlayers (4) voices are heard per phrase. Phrases without any audio are left out.
 * Returns { buildId, items: [{ id, lang, text, players: [{ label, candidate, src }] }] }.
 */
export function buildListenData(phrases, results, { maxPlayers = 4 } = {}) {
  const labels = "ABCDEFGH";
  const items = [];
  for (const phrase of phrases) {
    // Sorted first, so the order of the results file does not change the letters.
    const ok = results.filter((r) => r.id === phrase.id && r.file && !r.error).sort((a, b) => a.candidate.localeCompare(b.candidate));
    if (ok.length === 0) continue;
    const names = ok.map((r) => r.candidate).sort();
    const random = seededRandom(hash32(`${phrase.id}|${names.join(",")}`));
    const chosen = shuffled(ok, random).slice(0, Math.min(maxPlayers, labels.length));
    items.push({
      id: phrase.id,
      lang: phrase.lang,
      text: phrase.text,
      players: chosen.map((r, i) => ({ label: labels[i], candidate: r.candidate, src: r.file.split("/").map(encodeURIComponent).join("/") })),
    });
  }
  const buildId = hash32(JSON.stringify(items.map((i) => [i.id, i.players.map((p) => [p.label, p.candidate, p.src])]))).toString(16).padStart(8, "0");
  return { buildId, items };
}

const escapeHtml = (text) => String(text).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");

const STYLE = `
:root { color-scheme: light dark; --accent: #0b6bcb; --good: #2e7d32; }
* { box-sizing: border-box; }
body { font-family: system-ui, sans-serif; margin: 0 auto; padding: 1rem; max-width: 56rem; line-height: 1.4; }
header { display: flex; flex-wrap: wrap; gap: .5rem 1rem; align-items: center; justify-content: space-between; }
h1 { font-size: 1.1rem; margin: 0; }
progress { width: 100%; height: .9rem; }
button { font: inherit; padding: .4rem .8rem; border-radius: .4rem; border: 1px solid #8887; cursor: pointer; }
button:disabled { opacity: .4; cursor: not-allowed; }
#phraseBox { margin: 1rem 0; padding: 1rem; border: 2px solid #8886; border-radius: .8rem; text-align: center; }
#phrase { font-size: clamp(1.4rem, 3.5vw, 2.2rem); font-weight: 600; }
#lang { font-size: .85rem; opacity: .8; }
.player { margin: .8rem 0; padding: .8rem; border: 2px solid #8885; border-radius: .6rem; }
.player.active { border-color: var(--accent); }
.player.rated { background: #2e7d3212; }
.player.broken { border-color: #c62828; }
.player h2 { margin: 0 0 .4rem; font-size: 1.6rem; }
audio { width: 100%; }
.rating { display: flex; flex-wrap: wrap; gap: .35rem; align-items: center; margin: .45rem 0; }
.rating span { flex: 1 1 18rem; }
.rating button { min-width: 2.4rem; }
.rating button.on { background: var(--accent); color: #fff; border-color: var(--accent); }
input[type=text] { width: 100%; padding: .4rem; font: inherit; }
#bar { display: flex; flex-wrap: wrap; gap: .5rem; justify-content: center; margin: 1rem 0; }
#help, #saveState, #exportState { font-size: .85rem; opacity: .85; }
kbd { border: 1px solid #8888; border-radius: .25rem; padding: 0 .3rem; font-size: .85em; }
`;

/**
 * The listening page as one string. data comes from buildListenData; clientScript is the text of web/listen-client.js.
 * Audio is referenced by relative path, so listen.html must stay in voice-lab/out/ next to the tts/ folder.
 */
export function renderListenHtml(data, clientScript, { title = "Voice lab - listening test" } = {}) {
  const json = JSON.stringify(data).replace(/</g, "\\u003c").replace(/\u2028/g, "\\u2028").replace(/\u2029/g, "\\u2029");
  const script = String(clientScript).replace(/<\/script/gi, "<\\/script");
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${escapeHtml(title)}</title>
<style>${STYLE}</style>
</head>
<body>
<header>
  <h1>${escapeHtml(title)} (blind: you will not see which voice is which)</h1>
  <button id="export" type="button">Export ratings</button>
</header>
<progress id="progressBar" value="0" max="1"></progress>
<div id="progressText"></div>
<main id="phraseBox">
  <div id="lang"></div>
  <div id="phrase"></div>
</main>
<div id="players"></div>
<div id="bar">
  <button id="prev" type="button">&larr; Previous</button>
  <button id="nextUnrated" type="button">Next not rated yet</button>
  <button id="next" type="button">Next &rarr;</button>
</div>
<p id="help">
  Keys: <kbd>A</kbd> <kbd>B</kbd> <kbd>C</kbd> <kbd>D</kbd> play / pause that voice, <kbd>Space</kbd> play / pause the selected voice,
  <kbd>1</kbd>-<kbd>5</kbd> "sounds like a person", <kbd>Shift</kbd>+<kbd>1</kbd>-<kbd>5</kbd> "I would trust it for an allergy warning",
  <kbd>M</kbd> write a comment, <kbd>&larr;</kbd> <kbd>&rarr;</kbd> previous / next phrase, <kbd>N</kbd> next phrase not rated yet.
  Pressing the same number again clears it. 1 is bad, 5 is great.
</p>
<p id="saveState"></p>
<p id="exportState"></p>
<script id="data" type="application/json">${json}</script>
<script>${script}</script>
</body>
</html>
`;
}
