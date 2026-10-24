import { describe, expect, it } from "vitest";
import { centsToInput, formatCents, parsePriceToCents } from "./money";

describe("menu prices", () => {
  it("reads euros typed with a comma or a dot", () => {
    expect(parsePriceToCents("12,50")).toBe(1250);
    expect(parsePriceToCents("12.5")).toBe(1250);
    expect(parsePriceToCents("9")).toBe(900);
    expect(parsePriceToCents(" 0,99 ")).toBe(99);
  });

  it("treats an empty field as no price", () => {
    expect(parsePriceToCents("")).toBeUndefined();
    expect(parsePriceToCents("   ")).toBeUndefined();
  });

  it("rejects anything that is not a price", () => {
    expect(parsePriceToCents("abc")).toBeNull();
    expect(parsePriceToCents("-3")).toBeNull();
    expect(parsePriceToCents("1,234")).toBeNull();
    expect(parsePriceToCents("12,5,0")).toBeNull();
  });

  it("avoids floating point errors by working in cents", () => {
    expect(parsePriceToCents("0,29")).toBe(29); // 0.29 * 100 is 28.999999999999996 in floating point
    expect(parsePriceToCents("1,15")).toBe(115);
  });

  it("shows cents as euros in the right language", () => {
    expect(formatCents(1250, "en")).toBe("€12.50");
    expect(formatCents(1250, "it")).toContain("12,50");
    expect(centsToInput(1250)).toBe("12.50");
    expect(centsToInput(null)).toBe("");
  });
});
