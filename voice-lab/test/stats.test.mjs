import { test } from "node:test";
import assert from "node:assert/strict";
import { max, mean, median, min, percentile, summarize } from "../lib/stats.mjs";

const close = (actual, expected, message) => assert.ok(Math.abs(actual - expected) < 1e-9, message ?? `${actual} is not close to ${expected}`);

test("mean", () => {
  assert.equal(mean([1, 2, 3, 6]), 3);
  assert.equal(mean([]), null);
});

test("median of odd and even lists, in any order", () => {
  assert.equal(median([1, 3, 2]), 2);
  assert.equal(median([4, 1, 3, 2]), 2.5);
  assert.equal(median([7]), 7);
});

test("median of an empty list is null", () => {
  assert.equal(median([]), null);
});

test("percentile interpolates between the two nearest values", () => {
  close(percentile([10, 20, 30, 40, 50], 90), 46);
  close(percentile([10, 20, 30, 40, 50], 50), 30);
  close(percentile([10, 20, 30, 40, 50], 25), 20);
});

test("percentile 0 is the smallest and 100 the largest; a single value is returned as is", () => {
  assert.equal(percentile([5, 1, 9], 0), 1);
  assert.equal(percentile([5, 1, 9], 100), 9);
  assert.equal(percentile([5], 90), 5);
});

test("percentile does not change its input and rejects a bad p", () => {
  const input = [3, 1, 2];
  percentile(input, 50);
  assert.deepEqual(input, [3, 1, 2]);
  assert.throws(() => percentile(input, 101), RangeError);
  assert.throws(() => percentile(input, -1), RangeError);
  assert.equal(percentile([], 50), null);
});

test("min and max", () => {
  assert.equal(min([3, 1, 2]), 1);
  assert.equal(max([3, 1, 2]), 3);
  assert.equal(min([]), null);
  assert.equal(max([]), null);
});

test("summarize", () => {
  const s = summarize([100, 200, 300]);
  assert.equal(s.n, 3);
  assert.equal(s.min, 100);
  assert.equal(s.median, 200);
  assert.equal(s.max, 300);
  assert.equal(s.mean, 200);
  assert.deepEqual(summarize([]), { n: 0, min: null, median: null, max: null, mean: null, p90: null });
});
