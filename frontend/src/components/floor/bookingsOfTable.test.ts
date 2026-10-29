import { describe, expect, it } from "vitest";
import type { components } from "@/api/schema";
import { bookingsForTable } from "./bookingsOfTable";

type Reservation = components["schemas"]["Reservation"];

let counter = 0;
function booking(over: Partial<Reservation>): Reservation {
  counter += 1;
  return {
    id: `r${counter}`,
    tableId: "t1",
    guestName: `Guest ${counter}`,
    phone: "",
    email: null,
    startsAt: "2026-10-29T18:00:00Z",
    durationMinutes: 90,
    partySize: 2,
    highChairs: 0,
    strollerSpaces: 0,
    status: "CONFIRMED",
    allergens: [],
    notes: "",
    ...over,
  };
}

describe("bookingsForTable", () => {
  it("is empty when nobody is booked", () => {
    expect(bookingsForTable([], "t1")).toEqual([]);
  });

  it("keeps only the bookings of the given table", () => {
    const mine = booking({ tableId: "t1" });
    const list = [mine, booking({ tableId: "t2" }), booking({ tableId: null }), booking({ tableId: undefined })];
    expect(bookingsForTable(list, "t1")).toEqual([mine]);
  });

  it("drops cancelled, completed and no-show bookings", () => {
    const list = [
      booking({ status: "CANCELLED" }),
      booking({ status: "COMPLETED" }),
      booking({ status: "NO_SHOW" }),
      booking({ status: "PENDING", guestName: "Pending" }),
      booking({ status: "CONFIRMED", guestName: "Confirmed" }),
      booking({ status: "ARRIVED", guestName: "Arrived" }),
    ];
    expect(bookingsForTable(list, "t1").map((r) => r.guestName).sort()).toEqual(["Arrived", "Confirmed", "Pending"]);
  });

  it("puts the seated party first, then the rest by start time", () => {
    const late = booking({ guestName: "Late", startsAt: "2026-10-29T20:30:00Z" });
    const early = booking({ guestName: "Early", startsAt: "2026-10-29T17:00:00Z", status: "PENDING" });
    const seated = booking({ guestName: "Seated", startsAt: "2026-10-29T19:00:00Z", status: "ARRIVED" });
    expect(bookingsForTable([late, seated, early], "t1").map((r) => r.guestName)).toEqual(["Seated", "Early", "Late"]);
  });

  it("orders two bookings on one table by time", () => {
    const second = booking({ guestName: "Second", startsAt: "2026-10-29T20:00:00Z" });
    const first = booking({ guestName: "First", startsAt: "2026-10-29T18:00:00Z" });
    expect(bookingsForTable([second, first], "t1").map((r) => r.guestName)).toEqual(["First", "Second"]);
  });

  it("does not change the list it was given", () => {
    const list = [booking({ startsAt: "2026-10-29T20:00:00Z" }), booking({ startsAt: "2026-10-29T18:00:00Z" }), booking({ tableId: "t2" })];
    const before = list.map((r) => r.id);
    bookingsForTable(list, "t1");
    expect(list.map((r) => r.id)).toEqual(before);
    expect(list).toHaveLength(3);
  });
});
