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

// --- Times of day, always on the 24-hour clock ("20:30"; never AM or PM)

/**
 * Reads a time someone typed and returns it as "HH:MM", or null if it is not a valid 24-hour time.
 * Accepts "20:30", "2030", "830" and "8:30".
 */
export function parseTime(text: string): string | null {
  const match = /^(\d{1,2}):?(\d{2})$/.exec(text.trim());
  if (!match) return null;
  const hours = Number(match[1]);
  const minutes = Number(match[2]);
  if (hours > 23 || minutes > 59) return null;
  return `${pad(hours)}:${pad(minutes)}`;
}

/** Keeps a time field tidy while typing: only digits, and the colon appears by itself ("2030" becomes "20:30"). */
export function formatTimeTyping(raw: string): string {
  const digits = raw.replace(/\D/g, "").slice(0, 4);
  return digits.length <= 2 ? digits : `${digits.slice(0, 2)}:${digits.slice(2)}`;
}

function toMinutes(time: string): number {
  const [h, m] = time.split(":").map(Number);
  return h * 60 + m;
}

/** Minutes from one time to the next time it is on the clock; an end earlier than the start means after midnight. */
export function minutesBetween(start: string, end: string): number {
  const diff = toMinutes(end) - toMinutes(start);
  return diff <= 0 ? diff + 24 * 60 : diff;
}

/** The time a number of minutes later, wrapping past midnight. */
export function addMinutes(time: string, minutes: number): string {
  const total = (((toMinutes(time) + minutes) % (24 * 60)) + 24 * 60) % (24 * 60);
  return `${pad(Math.floor(total / 60))}:${pad(total % 60)}`;
}

/** When a reservation that starts at the given instant and lasts that many minutes ends, as "HH:MM". */
export function endTime(startIso: string, durationMinutes: number): string {
  return addMinutes(timeOf(startIso), durationMinutes);
}

/** "1 h 30 min", "45 min", "2 h". */
export function humanDuration(minutes: number): string {
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  return [h > 0 ? `${h} h` : "", m > 0 ? `${m} min` : ""].filter(Boolean).join(" ");
}
