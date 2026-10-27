import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { CANDIDATES_EXAMPLE_PATH, extensionForFormat, joinUrl, parseArgs, safeName, validateCandidates } from "../lib/config.mjs";

test("parseArgs: values, =, booleans and positional words", () => {
  const { flags, positional } = parseArgs(["--only", "a", "--limit", "3", "take.wav", "--comments", "other", "--lang=it"], { booleans: ["comments"] });
  assert.deepEqual(flags, { only: "a", limit: "3", comments: true, lang: "it" });
  assert.deepEqual(positional, ["take.wav", "other"]);
});

test("parseArgs: a flag without a value becomes true", () => {
  assert.deepEqual(parseArgs(["--verbose"]).flags, { verbose: true });
  assert.deepEqual(parseArgs(["--a", "--b", "x"]).flags, { a: true, b: "x" });
});

test("joinUrl puts exactly one slash between base and path", () => {
  assert.equal(joinUrl("http://localhost:8000", "/v1/audio/speech"), "http://localhost:8000/v1/audio/speech");
  assert.equal(joinUrl("http://localhost:8000/", "v1/x"), "http://localhost:8000/v1/x");
  assert.equal(joinUrl("http://h/", "//v1"), "http://h/v1");
});

test("safeName keeps only letters, digits, dot, underscore and dash", () => {
  assert.equal(safeName("kokoro it/sara"), "kokoro_it_sara");
  assert.equal(safeName("ok-name_1.2"), "ok-name_1.2");
  assert.equal(safeName("../x"), ".._x");
});

test("extensionForFormat", () => {
  assert.equal(extensionForFormat("mp3"), "mp3");
  assert.equal(extensionForFormat("WAV"), "wav");
  assert.equal(extensionForFormat("opus"), "opus");
  assert.equal(extensionForFormat(undefined), "mp3");
  assert.equal(extensionForFormat("strange"), "mp3");
});

test("the shipped candidates.example.json is valid for both kinds", () => {
  const example = JSON.parse(readFileSync(CANDIDATES_EXAMPLE_PATH, "utf8"));
  assert.deepEqual(validateCandidates(example, "stt"), []);
  assert.deepEqual(validateCandidates(example, "tts"), []);
});

test("validateCandidates reports what is wrong", () => {
  assert.match(validateCandidates({}, "stt")[0], /at least one/);
  assert.match(validateCandidates({ stt: [{ name: "a", baseUrl: "localhost:8000", model: "m" }] }, "stt").join(" "), /baseUrl/);
  assert.match(validateCandidates({ stt: [{ name: "a", baseUrl: "http://h", model: "" }] }, "stt").join(" "), /model/);
  assert.match(validateCandidates({ stt: [{ baseUrl: "http://h", model: "m" }] }, "stt").join(" "), /name/);
  const duplicate = { name: "a", baseUrl: "http://h", model: "m" };
  assert.match(validateCandidates({ stt: [duplicate, { ...duplicate }] }, "stt").join(" "), /duplicate/);
  assert.match(validateCandidates({ tts: [{ name: "a", baseUrl: "http://h", model: "m" }] }, "tts").join(" "), /voices/);
  assert.match(validateCandidates({ stt: [{ name: "a", baseUrl: "http://h", model: "m", headers: ["x"] }] }, "stt").join(" "), /headers/);
});

test("validateCandidates accepts optional headers", () => {
  const config = { stt: [{ name: "hosted", baseUrl: "https://example.test", model: "m", headers: { Authorization: "Bearer PLACEHOLDER" } }] };
  assert.deepEqual(validateCandidates(config, "stt"), []);
});
