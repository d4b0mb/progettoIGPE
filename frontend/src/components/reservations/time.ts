/**
 * Dates and times for reservations.
 *
 * A reservation day is a plain date ("2026-10-24") and a time of day ("20:30"),
 * both as the staff see them on the wall clock. The API wants an exact instant,
 * so these helpers convert at the edge. All of it uses the device's own time
 * zone, which for staff standing in the restaurant is the restaurant's.
 */

const pad = (n: number) => String(n).padStart(2, "0");

/** Today as YYYY-MM-DD on the device's clock. */
export function todayLocal(now: Date = new Date()): string {
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
}

/** The date a number of days before or after. Done in UTC so daylight-saving changes cannot skip or repeat a day. */
export function shiftDay(date: string, days: number): string {
  const [y, m, d] = date.split("-").map(Number);
  const shifted = new Date(Date.UTC(y, m - 1, d + days));
  return `${shifted.getUTCFullYear()}-${pad(shifted.getUTCMonth() + 1)}-${pad(shifted.getUTCDate())}`;
}

/** The exact moment (as an ISO string in UTC) of a date and wall-clock time on this device. */
export function toInstant(date: string, time: string): string {
  return new Date(`${date}T${time}`).toISOString();
}

/** HH:mm of an ISO instant, on the device's clock. */
export function timeOf(iso: string): string {
  const d = new Date(iso);
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** YYYY-MM-DD of an ISO instant, on the device's clock. */
export function dateOf(iso: string): string {
  return todayLocal(new Date(iso));
}
