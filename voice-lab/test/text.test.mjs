import { test } from "node:test";
import assert from "node:assert/strict";
import { charErrorRate, levenshtein, normalizeText, normalizedWords, numberToWordsEn, numberToWordsIt, wordErrorRate } from "../lib/text.mjs";

const close = (actual, expected) => assert.ok(Math.abs(actual - expected) < 1e-9, `${actual} is not close to ${expected}`);

// ---- numbers
test("Italian numbers 0-999", () => {
  const expected = { 0: "zero", 7: "sette", 13: "tredici", 21: "ventuno", 23: "ventitré", 28: "ventotto", 30: "trenta", 99: "novantanove", 100: "cento", 101: "centouno", 108: "centotto", 180: "centottanta", 200: "duecento", 321: "trecentoventuno", 999: "novecentonovantanove" };
  for (const [n, words] of Object.entries(expected)) assert.equal(numberToWordsIt(Number(n)), words, `n = ${n}`);
});

test("English numbers 0-999", () => {
  const expected = { 0: "zero", 9: "nine", 13: "thirteen", 21: "twenty one", 40: "forty", 100: "one hundred", 120: "one hundred twenty", 999: "nine hundred ninety nine" };
  for (const [n, words] of Object.entries(expected)) assert.equal(numberToWordsEn(Number(n)), words, `n = ${n}`);
});

test("number converters refuse values outside 0-999", () => {
  for (const bad of [-1, 1000, 1.5, Number.NaN]) {
    assert.throws(() => numberToWordsIt(bad), RangeError);
    assert.throws(() => numberToWordsEn(bad), RangeError);
  }
});

// ---- normalisation
test("lower case, accents and punctuation are removed (Italian)", () => {
  assert.equal(normalizeText("Àccènti, Perché?! È così.", "it"), "accenti perche e cosi");
});

test("spaces are collapsed and the text is trimmed", () => {
  assert.equal(normalizeText("  a   b \n c  ", "it"), "a b c");
  assert.equal(normalizeText("", "it"), "");
  assert.equal(normalizeText(null, "it"), "");
});

test('"alle 20:30" and "alle venti e trenta" are the same after normalising', () => {
  assert.equal(normalizeText("alle 20:30", "it"), "alle venti e trenta");
  assert.equal(normalizeText("alle venti e trenta", "it"), "alle venti e trenta");
  assert.equal(normalizeText("Conferma per le 20.30.", "it"), "conferma per le venti e trenta");
});

test("times on the hour and with minutes below ten", () => {
  assert.equal(normalizeText("alle 20:00", "it"), "alle venti");
  assert.equal(normalizeText("alle 8:05", "it"), "alle otto e cinque");
  assert.equal(normalizeText("at 20:30", "en"), "at twenty thirty");
  assert.equal(normalizeText("at 8:00", "en"), "at eight");
  assert.equal(normalizeText("at 8:05", "en"), "at eight oh five");
});

test("an impossible time is left as plain numbers", () => {
  assert.equal(normalizeText("25.99", "it"), "venticinque novantanove");
});

test("spoken Italian times match the digits form", () => {
  assert.equal(normalizeText("otto e mezza", "it"), normalizeText("8:30", "it"));
  assert.equal(normalizeText("mezzogiorno e mezza", "it"), normalizeText("12:30", "it"));
  assert.equal(normalizeText("nove e un quarto", "it"), normalizeText("9:15", "it"));
  assert.equal(normalizeText("Mezzogiorno", "it"), "dodici");
});

test("digits become words in the right language", () => {
  assert.equal(normalizeText("Prenota per 4", "it"), "prenota per quattro");
  assert.equal(normalizeText("tavolo 12", "it"), "tavolo dodici");
  assert.equal(normalizeText("table 12", "en"), "table twelve");
  assert.equal(normalizeText("4pers", "it"), "quattro pers");
});

test("long digit strings and numbers with a leading zero are read digit by digit", () => {
  assert.equal(normalizeText("0612", "it"), "zero sei uno due");
  assert.equal(normalizeText("4567", "en"), "four five six seven");
});

test("apostrophes: Italian elision separates words, English contractions and names close up", () => {
  assert.equal(normalizeText("un'allergia", "it"), "un allergia");
  assert.equal(normalizeText("un allergia", "it"), "un allergia");
  assert.equal(normalizeText("O'Brien", "en"), "obrien");
  assert.equal(normalizeText("O’Brien", "en"), "obrien");
  assert.equal(normalizeText("don't", "en"), "dont");
});

test("hyphens separate words", () => {
  assert.equal(normalizeText("quattro-formaggi", "it"), "quattro formaggi");
  assert.equal(normalizeText("twenty-one", "en"), "twenty one");
});

test("English p.m. and PM are the same", () => {
  assert.equal(normalizeText("at 8 p.m.", "en"), "at eight pm");
  assert.equal(normalizeText("at 8 PM", "en"), "at eight pm");
});

test("normalizedWords", () => {
  assert.deepEqual(normalizedWords("Ciao, mondo!", "it"), ["ciao", "mondo"]);
  assert.deepEqual(normalizedWords("...", "it"), []);
});

// ---- distances and rates
test("levenshtein on strings and arrays", () => {
  assert.equal(levenshtein("kitten", "sitting"), 3);
  assert.equal(levenshtein("", "abc"), 3);
  assert.equal(levenshtein("abc", ""), 3);
  assert.equal(levenshtein("same", "same"), 0);
  assert.equal(levenshtein(["a", "b", "c"], ["a", "x", "c"]), 1);
});

test("WER on known pairs", () => {
  close(wordErrorRate("il gatto dorme", "il gatto dorme"), 0);
  close(wordErrorRate("il gatto dorme", "il gatto"), 1 / 3); // one deletion
  close(wordErrorRate("a b c d", "a x c"), 0.5); // one substitution, one deletion
  close(wordErrorRate("a b", "a b c d"), 1); // two insertions
  close(wordErrorRate("uno due", "tre quattro"), 1);
});

test("WER can pass 1 when the engine adds many words", () => {
  assert.ok(wordErrorRate("a", "a b c d") > 1);
});

test("WER with an empty reference or hypothesis", () => {
  assert.equal(wordErrorRate("", ""), 0);
  assert.equal(wordErrorRate("", "something"), 1);
  assert.equal(wordErrorRate("something", ""), 1);
});

test("WER ignores case, accents, punctuation and writes numbers as words", () => {
  assert.equal(wordErrorRate("Prenota per quattro, domani alle 21:00.", "prenota per 4 domani alle ventuno"), 0);
  assert.equal(wordErrorRate("Perché no?", "perche no"), 0);
  assert.equal(wordErrorRate("Table for six at eight thirty", "table for 6 at 8:30", "en"), 0);
});

test("CER on known pairs", () => {
  close(charErrorRate("casa", "casa"), 0);
  close(charErrorRate("casa", "cassa"), 0.25); // one insertion in four characters
  close(charErrorRate("ab", ""), 1);
  assert.equal(charErrorRate("", ""), 0);
  assert.equal(charErrorRate("perché", "perche"), 0);
});
