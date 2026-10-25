import type { components } from "@/api/schema";

export type Entry = components["schemas"]["AuditEntry"];
export type Category = "ALL" | "SECURITY" | "STAFF" | "BOOKINGS" | "ORDERS" | "FLOOR" | "MENU" | "SYSTEM";
export type Range = "24h" | "7d" | "30d" | "90d";

export const CATEGORIES: Category[] = ["ALL", "SECURITY", "STAFF", "BOOKINGS", "ORDERS", "FLOOR", "MENU", "SYSTEM"];
export const RANGES: Range[] = ["24h", "7d", "30d", "90d"];

/** Which group a kind of action belongs to, for filtering. */
export function categoryOf(action: string): Exclude<Category, "ALL"> {
  if (/^(SIGNUP|LOGIN_|LOGOUT|TOKEN_|PASSWORD_|RECOVERY_|ACCESS_DENIED)/.test(action)) return "SECURITY";
  if (action.startsWith("STAFF_")) return "STAFF";
  if (action.startsWith("RESERVATION_")) return "BOOKINGS";
  if (action.startsWith("ORDER_")) return "ORDERS";
  if (/^(ROOM_|TABLE_|ELEMENT_)/.test(action)) return "FLOOR";
  if (action.startsWith("MENU_")) return "MENU";
  return "SYSTEM";
}

/** "code: C-001 · table: 3": the extra facts of an entry, minus the technical path which is noise to a person. */
export function describeDetails(details: Record<string, string>): string {
  return Object.entries(details)
    .filter(([key, value]) => key !== "path" && value !== "")
    .map(([key, value]) => `${key}: ${value}`)
    .join(" · ");
}

export function filterEntries(entries: Entry[], category: Category, failuresOnly: boolean): Entry[] {
  return entries.filter((e) => (category === "ALL" || categoryOf(e.action) === category) && (!failuresOnly || e.outcome === "FAILURE"));
}

const DAY_MS = 24 * 3600 * 1000;
const RANGE_DAYS: Record<Range, number> = { "24h": 1, "7d": 7, "30d": 30, "90d": 90 };

/** The start of a period as an ISO instant. */
export function rangeStart(range: Range, now: number): string {
  return new Date(now - RANGE_DAYS[range] * DAY_MS).toISOString();
}
