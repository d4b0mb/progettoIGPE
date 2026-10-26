import { describe, expect, it } from "vitest";
import { addMinutes, dateOf, endTime, formatTimeTyping, humanDuration, minutesBetween, parseTime, shiftDay, timeOf, todayLocal, toInstant } from "./time";

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

describe("24-hour times", () => {
  it("reads what people type and refuses what is not a time", () => {
    expect(parseTime("20:30")).toBe("20:30");
    expect(parseTime("2030")).toBe("20:30");
    expect(parseTime("830")).toBe("08:30");
    expect(parseTime("8:30")).toBe("08:30");
    expect(parseTime(" 00:05 ")).toBe("00:05");
    expect(parseTime("24:00")).toBeNull();
    expect(parseTime("12:60")).toBeNull();
    expect(parseTime("9 PM")).toBeNull();
    expect(parseTime("")).toBeNull();
  });

  it("puts the colon in while typing and ignores everything but digits", () => {
    expect(formatTimeTyping("2")).toBe("2");
    expect(formatTimeTyping("203")).toBe("20:3");
    expect(formatTimeTyping("2030")).toBe("20:30");
    expect(formatTimeTyping("20:30")).toBe("20:30");
    expect(formatTimeTyping("20301")).toBe("20:30");
    expect(formatTimeTyping("ab9")).toBe("9");
  });

  it("measures from a start to an end, also across midnight", () => {
    expect(minutesBetween("08:00", "09:30")).toBe(90);
    expect(minutesBetween("20:00", "22:00")).toBe(120);
    expect(minutesBetween("23:00", "00:30")).toBe(90);
    expect(minutesBetween("20:00", "20:00")).toBe(24 * 60);
  });

  it("adds minutes to a time and wraps past midnight", () => {
    expect(addMinutes("20:00", 120)).toBe("22:00");
    expect(addMinutes("23:30", 60)).toBe("00:30");
    expect(addMinutes("00:10", -20)).toBe("23:50");
  });

  it("works out the end of a reservation from its start and length", () => {
    expect(endTime(toInstant("2026-10-24", "20:00"), 90)).toBe("21:30");
  });

  it("writes a length for people", () => {
    expect(humanDuration(90)).toBe("1 h 30 min");
    expect(humanDuration(45)).toBe("45 min");
    expect(humanDuration(120)).toBe("2 h");
  });
});
