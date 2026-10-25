import type { components } from "@/api/schema";

export type Ticket = components["schemas"]["TicketView"];
export type Priority = Ticket["priority"];
export type TicketStatus = Ticket["status"];

const LEVEL: Record<Priority, number> = { NORMAL: 0, HIGH: 1, URGENT: 2, VIP: 3 };

/** A serving that has waited this long (counted from when it reached the kitchen) without being served is flagged as late. */
export const LATE_AFTER_MINUTES = 15;

/** Kitchen queue order: highest priority first, then the one that has waited longest. */
export function byQueueOrder(a: Ticket, b: Ticket): number {
  return LEVEL[b.priority] - LEVEL[a.priority] || a.servingSince.localeCompare(b.servingSince);
}

/** Tickets are on screen until they are served or cancelled. */
export function isActive(status: TicketStatus): boolean {
  return status === "NEW" || status === "PREPARING" || status === "READY";
}

/**
 * Applies one change from the live stream to the list of tickets on screen:
 * a new or updated ticket replaces its old version, and a finished one disappears.
 */
export function applyTicket(current: Ticket[], incoming: Ticket): Ticket[] {
  const without = current.filter((t) => t.id !== incoming.id);
  return (isActive(incoming.status) ? [...without, incoming] : without).sort(byQueueOrder);
}

export function minutesSince(iso: string, now: number): number {
  return Math.max(0, Math.floor((now - new Date(iso).getTime()) / 60_000));
}

export function isLate(ticket: Ticket, now: number): boolean {
  return (ticket.status === "NEW" || ticket.status === "PREPARING") && minutesSince(ticket.servingSince, now) >= LATE_AFTER_MINUTES;
}

/** "7 min" or "1h 05m". */
export function elapsedText(minutes: number): string {
  return minutes < 60 ? `${minutes} min` : `${Math.floor(minutes / 60)}h ${String(minutes % 60).padStart(2, "0")}m`;
}

/** The distinct serving numbers of a ticket, lowest first (they may have gaps: 1, 3). */
export function servingNumbers(ticket: Ticket): number[] {
  return [...new Set(ticket.items.map((i) => i.serving))].sort((a, b) => a - b);
}

/** Which serving of how many is being cooked now, e.g. 2 of 3. */
export function servingPosition(ticket: Ticket): { index: number; count: number } {
  const numbers = servingNumbers(ticket);
  return { index: Math.max(0, numbers.indexOf(ticket.currentServing)) + 1, count: Math.max(1, numbers.length) };
}
