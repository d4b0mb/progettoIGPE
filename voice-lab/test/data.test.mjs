import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { PHRASES_PATH } from "../lib/config.mjs";

const phrases = JSON.parse(readFileSync(PHRASES_PATH, "utf8"));
const words = (text) => text.trim().split(/\s+/).length;

test("phrases.json has 60 speech-to-text phrases (40 Italian, 20 English)", () => {
  assert.equal(phrases.stt.length, 60);
  assert.equal(phrases.stt.filter((p) => p.lang === "it").length, 40);
  assert.equal(phrases.stt.filter((p) => p.lang === "en").length, 20);
});

test("phrases.json has 24 text-to-speech sentences (16 Italian, 8 English)", () => {
  assert.equal(phrases.tts.length, 24);
  assert.equal(phrases.tts.filter((p) => p.lang === "it").length, 16);
  assert.equal(phrases.tts.filter((p) => p.lang === "en").length, 8);
});

test("every phrase has a unique safe id, a language and text", () => {
  const ids = [...phrases.stt, ...phrases.tts].map((p) => p.id);
  assert.equal(new Set(ids).size, ids.length);
  for (const p of [...phrases.stt, ...phrases.tts]) {
    assert.match(p.id, /^[A-Za-z0-9][A-Za-z0-9._-]*$/);
    assert.ok(["it", "en"].includes(p.lang), p.id);
    assert.ok(typeof p.text === "string" && p.text.length > 0, p.id);
    assert.ok(p.notes === undefined || typeof p.notes === "string", p.id);
  }
});

test("speech-to-text phrases are written as spoken: no digits", () => {
  for (const p of phrases.stt) assert.doesNotMatch(p.text, /\d/, p.id);
});

test("the sentences marked long are 30-40 words", () => {
  const long = phrases.tts.filter((p) => p.notes?.startsWith("long"));
  assert.ok(long.length >= 4);
  for (const p of long) assert.ok(words(p.text) >= 30 && words(p.text) <= 40, `${p.id} has ${words(p.text)} words`);
});

test("the guest surnames and the hard cases are present", () => {
  const all = phrases.stt.map((p) => p.text).join(" ");
  for (const name of ["Rossi", "Bianchi", "Esposito", "Ferrari", "O'Brien", "Nguyen", "De Luca"]) assert.ok(all.includes(name), name);
  assert.ok(phrases.stt.filter((p) => p.notes?.startsWith("HARD")).length >= 5);
});
