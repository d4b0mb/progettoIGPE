import type { components } from "@/api/schema";

type Reservation = components["schemas"]["Reservation"];

/** Statuses that still hold the table today; finished, cancelled and no-show bookings do not. */
const ACTIVE: Reservation["status"][] = ["PENDING", "CONFIRMED", "ARRIVED"];

/**
 * Today's live bookings of one table: the party seated right now first, then the rest by start time.
 * Returns a new list; the input is left as it is.
 */
export function bookingsForTable(today: Reservation[], tableId: string): Reservation[] {
  const rank = (r: Reservation) => (r.status === "ARRIVED" ? 0 : 1);
  return today
    .filter((r) => r.tableId === tableId && ACTIVE.includes(r.status))
    .sort((a, b) => rank(a) - rank(b) || Date.parse(a.startsAt) - Date.parse(b.startsAt));
}
