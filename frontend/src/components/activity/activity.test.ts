import { describe, expect, it } from "vitest";
import { categoryOf, describeDetails, filterEntries, rangeStart, type Entry } from "./activity";

function entry(action: string, outcome: "SUCCESS" | "FAILURE" = "SUCCESS"): Entry {
  return { id: action, occurredAt: "2026-10-25T10:00:00Z", actorUserId: null, actorName: "x", action, targetType: null, targetId: null, outcome, details: {}, ip: null };
}

describe("activity log helpers", () => {
  it("groups actions into the categories people think in", () => {
    expect(categoryOf("LOGIN_FAILED")).toBe("SECURITY");
    expect(categoryOf("PASSWORD_RESET_BY_OWNER")).toBe("SECURITY");
    expect(categoryOf("ACCESS_DENIED")).toBe("SECURITY");
    expect(categoryOf("STAFF_CREATED")).toBe("STAFF");
    expect(categoryOf("RESERVATION_STATUS_CHANGED")).toBe("BOOKINGS");
    expect(categoryOf("ORDER_CREATED")).toBe("ORDERS");
    expect(categoryOf("TABLE_UPDATED")).toBe("FLOOR");
    expect(categoryOf("ELEMENT_DELETED")).toBe("FLOOR");
    expect(categoryOf("MENU_ITEM_CREATED")).toBe("MENU");
    expect(categoryOf("RETENTION_RUN")).toBe("SYSTEM");
    expect(categoryOf("SOMETHING_NEW")).toBe("SYSTEM");
  });

  it("shows details as plain facts and hides the technical path", () => {
    expect(describeDetails({ code: "C-001", table: "3", path: "/api/v1/orders" })).toBe("code: C-001 · table: 3");
    expect(describeDetails({ reason: "wrong password" })).toBe("reason: wrong password");
    expect(describeDetails({})).toBe("");
    expect(describeDetails({ note: "" })).toBe("");
  });

  it("filters by category and by failures", () => {
    const all = [entry("LOGIN_FAILED", "FAILURE"), entry("LOGIN_SUCCESS"), entry("ORDER_CREATED"), entry("ACCESS_DENIED", "FAILURE")];
    expect(filterEntries(all, "ALL", false)).toHaveLength(4);
    expect(filterEntries(all, "SECURITY", false)).toHaveLength(3);
    expect(filterEntries(all, "ORDERS", false).map((e) => e.action)).toEqual(["ORDER_CREATED"]);
    expect(filterEntries(all, "ALL", true).map((e) => e.action)).toEqual(["LOGIN_FAILED", "ACCESS_DENIED"]);
    expect(filterEntries(all, "ORDERS", true)).toEqual([]);
  });

  it("works out where a period starts", () => {
    const now = new Date("2026-10-25T12:00:00Z").getTime();
    expect(rangeStart("24h", now)).toBe("2026-10-24T12:00:00.000Z");
    expect(rangeStart("7d", now)).toBe("2026-10-18T12:00:00.000Z");
    expect(rangeStart("90d", now)).toBe("2026-07-27T12:00:00.000Z");
  });
});
