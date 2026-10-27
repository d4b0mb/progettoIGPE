// Output formatting: CSV and plain-text tables. Pure functions.

/** One CSV field: quoted when it contains a comma, a quote or a line break; quotes are doubled. */
export function csvField(value) {
  if (value === null || value === undefined) return "";
  const text = String(value);
  return /[",\r\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text;
}

/** Rows (arrays) to CSV text with a header line. Lines end with \n. */
export function toCsv(headers, rows) {
  return [headers, ...rows].map((row) => row.map(csvField).join(",")).join("\n") + "\n";
}

/**
 * A fixed-width text table. Cells are converted with String(); null and undefined show as "-".
 * Columns listed in rightAlign (by index) are right aligned (use for numbers).
 */
export function formatTable(headers, rows, { rightAlign = [] } = {}) {
  const cell = (v) => (v === null || v === undefined ? "-" : String(v));
  const all = [headers.map(cell), ...rows.map((r) => r.map(cell))];
  const widths = headers.map((_, c) => Math.max(...all.map((r) => r[c].length)));
  const line = (r) => r.map((v, c) => (rightAlign.includes(c) ? v.padStart(widths[c]) : v.padEnd(widths[c]))).join("  ").trimEnd();
  const rule = widths.map((w) => "-".repeat(w)).join("  ");
  return [line(all[0]), rule, ...all.slice(1).map(line)].join("\n");
}

/** A number with a fixed count of decimals, or "-" when it is null/undefined/NaN. */
export function fixed(value, digits = 1) {
  return value === null || value === undefined || Number.isNaN(value) ? "-" : Number(value).toFixed(digits);
}

/** A rate such as 0.1234 as a percentage with one decimal: "12.3%". */
export function percent(value) {
  return value === null || value === undefined || Number.isNaN(value) ? "-" : `${(value * 100).toFixed(1)}%`;
}
