import { test } from "node:test";
import assert from "node:assert/strict";
import { csvField, fixed, formatTable, percent, toCsv } from "../lib/format.mjs";

test("csvField quotes only when needed and doubles quotes", () => {
  assert.equal(csvField("plain"), "plain");
  assert.equal(csvField("a,b"), '"a,b"');
  assert.equal(csvField('he said "hi"'), '"he said ""hi"""');
  assert.equal(csvField("two\nlines"), '"two\nlines"');
  assert.equal(csvField(null), "");
  assert.equal(csvField(undefined), "");
  assert.equal(csvField(5), "5");
});

test("toCsv writes a header and rows", () => {
  assert.equal(toCsv(["a", "b"], [[1, "x,y"], [2, "z"]]), 'a,b\n1,"x,y"\n2,z\n');
});

test("formatTable aligns columns and right-aligns the listed ones", () => {
  const out = formatTable(["name", "n"], [["abc", 5], ["d", 10]], { rightAlign: [1] });
  assert.deepEqual(out.split("\n"), ["name   n", "----  --", "abc    5", `d${" ".repeat(5)}10`]);
});

test("formatTable shows null and undefined as a dash", () => {
  const out = formatTable(["a", "b"], [[null, undefined]]);
  assert.equal(out.split("\n")[2], "-  -");
});

test("fixed and percent", () => {
  assert.equal(fixed(1.234, 1), "1.2");
  assert.equal(fixed(2, 0), "2");
  assert.equal(fixed(null), "-");
  assert.equal(fixed(Number.NaN), "-");
  assert.equal(percent(0.12345), "12.3%");
  assert.equal(percent(0), "0.0%");
  assert.equal(percent(null), "-");
});
