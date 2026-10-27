import { test } from "node:test";
import assert from "node:assert/strict";
import { isInsideRelative, isLocalRequest, MAX_UPLOAD_BYTES, parseListDir, parseSaveName } from "../lib/save-rules.mjs";

test("parseSaveName accepts folder/file from the allow-list", () => {
  assert.deepEqual(parseSaveName("recordings/stt-it-01.wav"), { dir: "recordings", file: "stt-it-01.wav" });
  assert.deepEqual(parseSaveName("recordings/stt-it-01.json"), { dir: "recordings", file: "stt-it-01.json" });
  assert.deepEqual(parseSaveName("ratings/ratings.json"), { dir: "ratings", file: "ratings.json" });
  assert.deepEqual(parseSaveName("recordings/A_b-1.2.WAV"), { dir: "recordings", file: "A_b-1.2.WAV" });
});

test("parseSaveName refuses everything else", () => {
  const refused = [
    "", null, undefined, 42,
    "stt-it-01.wav", // no folder
    "../x.wav", "recordings/../x.wav", "recordings/..", "recordings/a..b.wav",
    "recordings/a/b.wav", // nested
    "out/a.wav", "tts/a.wav", "web/a.wav", "Recordings/a.wav", // folder not in the list
    "recordings/a b.wav", "recordings/a%2Fb.wav", "recordings/a;b.wav", // characters
    "recordings/.hidden.wav", "recordings/.wav", "recordings/-a.wav",
    "recordings/a.exe", "recordings/a.html", "recordings/a", "recordings/", // extension
    "recordings\\a.wav", "C:\\x.wav", "/etc/passwd", "recordings/a.wav\0",
    `recordings/${"a".repeat(130)}.wav`,
  ];
  for (const name of refused) assert.equal(parseSaveName(name), null, `should refuse ${JSON.stringify(name)}`);
});

test("parseListDir", () => {
  assert.equal(parseListDir("recordings"), "recordings");
  assert.equal(parseListDir("ratings"), "ratings");
  assert.equal(parseListDir("tts"), null);
  assert.equal(parseListDir("../"), null);
  assert.equal(parseListDir(null), null);
});

test("the upload limit is 20 MB", () => {
  assert.equal(MAX_UPLOAD_BYTES, 20 * 1024 * 1024);
});

test("isLocalRequest accepts this server's own host and origin only", () => {
  assert.equal(isLocalRequest({ host: "127.0.0.1:8099" }, 8099), true);
  assert.equal(isLocalRequest({ host: "localhost:8099", origin: "http://localhost:8099" }, 8099), true);
  assert.equal(isLocalRequest({ host: "127.0.0.1:8099", origin: "http://127.0.0.1:8099" }, 8099), true);
  assert.equal(isLocalRequest({ host: "127.0.0.1:8099", origin: "http://evil.example" }, 8099), false);
  assert.equal(isLocalRequest({ host: "evil.example" }, 8099), false);
  assert.equal(isLocalRequest({ host: "127.0.0.1:9999" }, 8099), false);
  assert.equal(isLocalRequest({}, 8099), false);
});

test("isInsideRelative", () => {
  assert.equal(isInsideRelative("a/b.txt"), true);
  assert.equal(isInsideRelative("a\\b.txt"), true);
  assert.equal(isInsideRelative(""), false);
  assert.equal(isInsideRelative("../x"), false);
  assert.equal(isInsideRelative("..\\x"), false);
  assert.equal(isInsideRelative("D:\\x"), false);
  assert.equal(isInsideRelative("/x"), false);
});
