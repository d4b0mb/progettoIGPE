import { test } from "node:test";
import assert from "node:assert/strict";
import { buildWavHeader, downmixToMono, encodeWav, floatTo16BitPCM, parseWav, pcm16BytesToFloat, resampleLinear } from "../lib/wav.mjs";

const text = (bytes, start, length) => String.fromCharCode(...bytes.slice(start, start + length));
const u16 = (bytes, at) => new DataView(bytes.buffer, bytes.byteOffset).getUint16(at, true);
const u32 = (bytes, at) => new DataView(bytes.buffer, bytes.byteOffset).getUint32(at, true);

test("header of a 16 kHz mono 16-bit file, byte by byte", () => {
  const h = buildWavHeader({ dataBytes: 32000 });
  assert.equal(h.length, 44);
  assert.equal(text(h, 0, 4), "RIFF");
  assert.equal(u32(h, 4), 36 + 32000);
  assert.equal(text(h, 8, 4), "WAVE");
  assert.equal(text(h, 12, 4), "fmt ");
  assert.equal(u32(h, 16), 16);
  assert.equal(u16(h, 20), 1); // PCM
  assert.equal(u16(h, 22), 1); // mono
  assert.equal(u32(h, 24), 16000);
  assert.equal(u32(h, 28), 32000); // bytes per second
  assert.equal(u16(h, 32), 2); // block align
  assert.equal(u16(h, 34), 16);
  assert.equal(text(h, 36, 4), "data");
  assert.equal(u32(h, 40), 32000);
});

test("header for stereo 44.1 kHz", () => {
  const h = buildWavHeader({ dataBytes: 1000, sampleRate: 44100, channels: 2 });
  assert.equal(u16(h, 22), 2);
  assert.equal(u32(h, 24), 44100);
  assert.equal(u32(h, 28), 176400);
  assert.equal(u16(h, 32), 4);
});

test("floats become 16-bit integers, clamped", () => {
  assert.deepEqual([...floatTo16BitPCM([0, 1, -1, 0.5, 2, -2])], [0, 32767, -32768, 16384, 32767, -32768]);
});

test("encodeWav writes header, then little-endian samples", () => {
  const file = encodeWav(Float32Array.from([0, 1, -1]), 16000);
  assert.equal(file.length, 44 + 6);
  assert.equal(u32(file, 40), 6);
  const view = new DataView(file.buffer);
  assert.equal(view.getInt16(44, true), 0);
  assert.equal(view.getInt16(46, true), 32767);
  assert.equal(view.getInt16(48, true), -32768);
});

test("parseWav reads back what encodeWav wrote, and the duration", () => {
  const samples = new Float32Array(16000).fill(0.25); // one second
  const info = parseWav(encodeWav(samples, 16000));
  assert.equal(info.sampleRate, 16000);
  assert.equal(info.channels, 1);
  assert.equal(info.bitsPerSample, 16);
  assert.equal(info.audioFormat, 1);
  assert.equal(info.dataOffset, 44);
  assert.equal(info.dataBytes, 32000);
  assert.equal(info.durationMs, 1000);
});

test("samples survive a round trip within one step of 16-bit precision", () => {
  const samples = Float32Array.from([0, 0.1, -0.1, 0.5, -0.5, 0.999]);
  const file = encodeWav(samples, 16000);
  const back = pcm16BytesToFloat(file, 44);
  assert.equal(back.length, samples.length);
  samples.forEach((s, i) => assert.ok(Math.abs(back[i] - s) < 1 / 16000));
});

test("parseWav skips extra chunks such as LIST", () => {
  const plain = encodeWav(new Float32Array(4), 16000);
  const withList = new Uint8Array(plain.length + 12);
  withList.set(plain.slice(0, 36), 0);
  withList.set([0x4c, 0x49, 0x53, 0x54, 4, 0, 0, 0, 1, 2, 3, 4], 36); // "LIST", size 4, 4 bytes
  withList.set(plain.slice(36), 48);
  const info = parseWav(withList);
  assert.equal(info.dataOffset, 48 + 8);
  assert.equal(info.dataBytes, 8);
});

test("parseWav refuses things that are not WAV files", () => {
  assert.throws(() => parseWav(new Uint8Array(5)), /short/i);
  assert.throws(() => parseWav(new TextEncoder().encode("this is not a wave file at all")), /RIFF/);
});

test("downmixToMono averages the channels", () => {
  assert.deepEqual([...downmixToMono([Float32Array.from([1, 0]), Float32Array.from([0, 1])])], [0.5, 0.5]);
  assert.deepEqual([...downmixToMono([Float32Array.from([0.2, 0.4])])].map((v) => Math.round(v * 10)), [2, 4]);
  assert.equal(downmixToMono([]).length, 0);
});

test("resampleLinear keeps a constant signal constant and gives the right length", () => {
  const out = resampleLinear(new Float32Array(480).fill(0.5), 48000, 16000);
  assert.equal(out.length, 160);
  assert.ok(out.every((v) => Math.abs(v - 0.5) < 1e-6));
  assert.equal(resampleLinear(new Float32Array(48000), 48000, 16000).length, 16000);
});

test("resampleLinear interpolates when upsampling and copies when the rate is unchanged", () => {
  assert.deepEqual([...resampleLinear(Float32Array.from([0, 1]), 1, 2)], [0, 0.5, 1, 1]);
  const same = Float32Array.from([1, 2, 3]);
  const copy = resampleLinear(same, 16000, 16000);
  assert.deepEqual([...copy], [1, 2, 3]);
  assert.notEqual(copy, same);
  assert.throws(() => resampleLinear(same, 0, 16000), RangeError);
});
