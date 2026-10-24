import { describe, expect, it } from "vitest";
import { mayOpen, navFor } from "./nav";

describe("who may open what", () => {
  it("lets the owner open everything and the kitchen only the kitchen", () => {
    for (const path of ["/reservations", "/floor", "/menu", "/staff", "/orders", "/kitchen"]) {
      expect(mayOpen("OWNER", path)).toBe(true);
    }
    expect(mayOpen("KITCHEN", "/kitchen")).toBe(true);
    for (const path of ["/reservations", "/floor", "/menu", "/staff", "/orders"]) {
      expect(mayOpen("KITCHEN", path)).toBe(false);
    }
  });

  it("keeps staff management for the owner", () => {
    expect(mayOpen("MANAGER", "/staff")).toBe(false);
    expect(mayOpen("WAITER", "/staff")).toBe(false);
  });

  it("leaves the home page and unknown paths open to anyone signed in", () => {
    expect(mayOpen("KITCHEN", "/")).toBe(true);
  });

  it("does not confuse a path that merely starts with the same letters", () => {
    expect(mayOpen("KITCHEN", "/kitchenette")).toBe(true);
    expect(mayOpen("KITCHEN", "/orders/new")).toBe(false);
  });

  it("builds the menu from the same list", () => {
    expect(navFor("KITCHEN").map((n) => n.href)).toEqual(["/kitchen"]);
  });
});
