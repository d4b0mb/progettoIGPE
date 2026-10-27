import { test } from "node:test";
import assert from "node:assert/strict";
import { buildListenData, hash32, renderListenHtml, seededRandom, shuffled } from "../lib/listen-page.mjs";

const phrases = [
  { id: "p1", lang: "it", text: "Ciao" },
  { id: "p2", lang: "en", text: "Hello" },
  { id: "p3", lang: "en", text: "Nothing was produced for this one" },
];
const ok = (candidate, id) => ({ candidate, id, file: `tts/${candidate}/${id}.mp3` });

test("hash32 is repeatable and spreads different texts", () => {
  assert.equal(hash32("abc"), hash32("abc"));
  assert.notEqual(hash32("abc"), hash32("abd"));
  assert.ok(hash32("anything") >= 0);
});

test("seededRandom gives the same numbers for the same seed, all in [0, 1)", () => {
  const a = seededRandom(42);
  const b = seededRandom(42);
  for (let i = 0; i < 50; i++) {
    const v = a();
    assert.equal(v, b());
    assert.ok(v >= 0 && v < 1);
  }
  assert.notEqual(seededRandom(1)(), seededRandom(2)());
});

test("shuffled keeps every element and leaves the input alone", () => {
  const input = [1, 2, 3, 4, 5, 6];
  const out = shuffled(input, seededRandom(7));
  assert.deepEqual([...out].sort(), input);
  assert.deepEqual(input, [1, 2, 3, 4, 5, 6]);
});

test("buildListenData labels the voices A, B, C and hides which is which in the labels", () => {
  const results = [ok("c1", "p1"), ok("c2", "p1"), ok("c3", "p1"), { candidate: "c1", id: "p2", file: null, error: "HTTP 500" }, ok("c2", "p2")];
  const data = buildListenData(phrases, results);
  assert.deepEqual(data.items.map((i) => i.id), ["p1", "p2"]); // p3 has no audio
  const p1 = data.items[0];
  assert.deepEqual(p1.players.map((p) => p.label), ["A", "B", "C"]);
  assert.deepEqual(p1.players.map((p) => p.candidate).sort(), ["c1", "c2", "c3"]);
  assert.ok(p1.players.every((p) => p.src === `tts/${p.candidate}/p1.mp3`));
  assert.deepEqual(data.items[1].players.map((p) => p.candidate), ["c2"]); // the failed one is left out
  assert.match(data.buildId, /^[0-9a-f]{8}$/);
});

test("the same audio gives the same letters and the same buildId; other audio gives another buildId", () => {
  const results = [ok("c1", "p1"), ok("c2", "p1"), ok("c3", "p1")];
  const first = buildListenData(phrases, results);
  const second = buildListenData(phrases, [...results].reverse());
  assert.deepEqual(first.items, second.items);
  assert.equal(first.buildId, second.buildId);
  assert.notEqual(first.buildId, buildListenData(phrases, [ok("c1", "p1"), ok("c2", "p1")]).buildId);
});

test("at most four voices are heard per phrase", () => {
  const results = ["c1", "c2", "c3", "c4", "c5", "c6"].map((c) => ok(c, "p1"));
  const players = buildListenData(phrases, results).items[0].players;
  assert.deepEqual(players.map((p) => p.label), ["A", "B", "C", "D"]);
  assert.equal(new Set(players.map((p) => p.candidate)).size, 4);
});

test("file names are URL-encoded part by part", () => {
  const data = buildListenData(phrases, [{ candidate: "c 1", id: "p1", file: "tts/c 1/p1.mp3" }]);
  assert.equal(data.items[0].players[0].src, "tts/c%201/p1.mp3");
});

test("renderListenHtml embeds the data safely and the script once", () => {
  const data = { buildId: "abcd1234", items: [{ id: "p1", lang: "it", text: "x</script><b>y", players: [{ label: "A", candidate: "c1", src: "tts/c1/p1.mp3" }] }] };
  const html = renderListenHtml(data, "var a = '</script>'; run();", { title: 'Test <&> "page"' });
  assert.equal(html.match(/<\/script>/g).length, 2); // the data block and the client script, nothing else
  assert.ok(html.includes("&lt;&amp;&gt; &quot;page&quot;"));
  const embedded = /<script id="data" type="application\/json">([\s\S]*?)<\/script>/.exec(html)[1];
  assert.deepEqual(JSON.parse(embedded), data);
  assert.ok(html.includes("run();"));
});
