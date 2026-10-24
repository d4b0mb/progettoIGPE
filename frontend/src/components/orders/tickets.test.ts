import { describe, expect, it } from "vitest";
import { applyTicket, byQueueOrder, elapsedText, isLate, minutesSince, type Ticket } from "./tickets";

function ticket(over: Partial<Ticket>): Ticket {
  return {
    id: "t1", code: "C-001", tableId: null, tableNumber: 3, reservationId: null, guestName: "", guestAllergens: [],
    priority: "NORMAL", status: "NEW", note: "", hasAllergyConflicts: false, items: [],
    createdAt: "2026-10-24T18:00:00Z", acknowledgedAt: null, readyAt: null, servedAt: null, ...over,
  };
}

describe("kitchen queue", () => {
  it("puts higher priority first, then the oldest", () => {
    const a = ticket({ id: "a", priority: "NORMAL", createdAt: "2026-10-24T18:00:00Z" });
    const b = ticket({ id: "b", priority: "VIP", createdAt: "2026-10-24T18:05:00Z" });
    const c = ticket({ id: "c", priority: "NORMAL", createdAt: "2026-10-24T18:01:00Z" });
    const d = ticket({ id: "d", priority: "URGENT", createdAt: "2026-10-24T18:09:00Z" });
    expect([a, b, c, d].sort(byQueueOrder).map((t) => t.id)).toEqual(["b", "d", "a", "c"]);
  });

  it("adds a new ticket in the right place", () => {
    const list = [ticket({ id: "a", priority: "HIGH" })];
    const next = applyTicket(list, ticket({ id: "b", priority: "VIP" }));
    expect(next.map((t) => t.id)).toEqual(["b", "a"]);
  });

  it("replaces a ticket when it changes status", () => {
    const list = [ticket({ id: "a", status: "NEW" })];
    const next = applyTicket(list, ticket({ id: "a", status: "PREPARING" }));
    expect(next).toHaveLength(1);
    expect(next[0].status).toBe("PREPARING");
  });

  it("removes a ticket once it is served or cancelled", () => {
    const list = [ticket({ id: "a", status: "READY" }), ticket({ id: "b" })];
    expect(applyTicket(list, ticket({ id: "a", status: "SERVED" })).map((t) => t.id)).toEqual(["b"]);
    expect(applyTicket(list, ticket({ id: "b", status: "CANCELLED" })).map((t) => t.id)).toEqual(["a"]);
  });

  it("does not mutate the list it was given", () => {
    const list = [ticket({ id: "a" })];
    applyTicket(list, ticket({ id: "b" }));
    expect(list).toHaveLength(1);
  });
});

describe("waiting time", () => {
  const now = new Date("2026-10-24T18:20:00Z").getTime();

  it("counts whole minutes and never goes negative", () => {
    expect(minutesSince("2026-10-24T18:07:30Z", now)).toBe(12);
    expect(minutesSince("2026-10-24T18:30:00Z", now)).toBe(0);
  });

  it("flags open tickets as late after 15 minutes, but not ready ones", () => {
    expect(isLate(ticket({ status: "NEW", createdAt: "2026-10-24T18:04:00Z" }), now)).toBe(true);
    expect(isLate(ticket({ status: "PREPARING", createdAt: "2026-10-24T18:10:00Z" }), now)).toBe(false);
    expect(isLate(ticket({ status: "READY", createdAt: "2026-10-24T17:00:00Z" }), now)).toBe(false);
  });

  it("writes elapsed time for humans", () => {
    expect(elapsedText(7)).toBe("7 min");
    expect(elapsedText(65)).toBe("1h 05m");
  });
});
