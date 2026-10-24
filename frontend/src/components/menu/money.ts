/**
 * Prices are stored as whole cents so there is never a rounding surprise.
 * People type them as euros, with a comma or a dot ("12,50" or "12.5").
 */

/** @returns cents, undefined for an empty field, or null if the text is not a valid price. */
export function parsePriceToCents(text: string): number | undefined | null {
  const cleaned = text.trim().replace(",", ".");
  if (cleaned === "") return undefined;
  if (!/^\d{1,5}(\.\d{1,2})?$/.test(cleaned)) return null;
  return Math.round(Number(cleaned) * 100);
}

export function formatCents(cents: number, locale: string): string {
  return new Intl.NumberFormat(locale, { style: "currency", currency: "EUR" }).format(cents / 100);
}

/** The text to show in a price field for an existing value. */
export function centsToInput(cents: number | null | undefined): string {
  return cents == null ? "" : (cents / 100).toFixed(2);
}
