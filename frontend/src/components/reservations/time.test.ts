import { describe, expect, it } from "vitest";
import { dateOf, shiftDay, timeOf, todayLocal, toInstant } from "./time";

describe("reservation dates and times", () => {
  it("formats today from the device clock", () => {
    expect(todayLocal(new Date(2026, 9, 24, 23, 59))).toBe("2026-10-24");
    expect(todayLocal(new Date(2026, 0, 5, 0, 0))).toBe("2026-01-05");
  });

  it("moves between days across month and year ends", () => {
    expect(shiftDay("2026-10-31", 1)).toBe("2026-11-01");
    expect(shiftDay("2026-01-01", -1)).toBe("2025-12-31");
    expect(shiftDay("2028-02-28", 1)).toBe("2028-02-29"); // leap year
  });

  it("does not skip or repeat a day when the clocks change", () => {
    // Clocks go back on 25 October 2026 in Italy, and forward on 29 March.
    expect(shiftDay("2026-10-24", 1)).toBe("2026-10-25");
    expect(shiftDay("2026-10-25", 1)).toBe("2026-10-26");
    expect(shiftDay("2026-03-28", 1)).toBe("2026-03-29");
    expect(shiftDay("2026-03-29", 1)).toBe("2026-03-30");
  });

  it("round-trips a wall-clock time through an instant", () => {
    const instant = toInstant("2026-10-24", "20:30");
    expect(instant).toMatch(/Z$/);
    expect(timeOf(instant)).toBe("20:30");
    expect(dateOf(instant)).toBe("2026-10-24");
  });
});
