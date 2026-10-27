// Small statistics helpers. Pure functions, no dependencies.

/** Arithmetic mean, or null for an empty list. */
export function mean(values) {
  if (!values.length) return null;
  return values.reduce((sum, v) => sum + v, 0) / values.length;
}

/**
 * Percentile with linear interpolation between the two nearest values (the "inclusive" method
 * most spreadsheets use). p is 0..100. Returns null for an empty list. Does not change the input.
 */
export function percentile(values, p) {
  if (!values.length) return null;
  if (p < 0 || p > 100 || Number.isNaN(p)) throw new RangeError("p must be between 0 and 100");
  const sorted = [...values].sort((a, b) => a - b);
  if (sorted.length === 1) return sorted[0];
  const rank = (p / 100) * (sorted.length - 1);
  const low = Math.floor(rank);
  const high = Math.ceil(rank);
  return sorted[low] + (sorted[high] - sorted[low]) * (rank - low);
}

/** Median (50th percentile). Null for an empty list. */
export function median(values) {
  return percentile(values, 50);
}

/** Smallest value, or null for an empty list. */
export function min(values) {
  return values.length ? Math.min(...values) : null;
}

/** Largest value, or null for an empty list. */
export function max(values) {
  return values.length ? Math.max(...values) : null;
}

/** { n, min, median, max, mean, p90 } for a list of numbers; the numeric fields are null when n is 0. */
export function summarize(values) {
  return { n: values.length, min: min(values), median: median(values), max: max(values), mean: mean(values), p90: percentile(values, 90) };
}
