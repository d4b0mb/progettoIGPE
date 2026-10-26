import { describe, expect, it } from "vitest";
import { addLine, isPlain, lineCents, nudgePlain, quantityOf, removeLine, replaceLine, type CartLine } from "./cart";

const line = (over: Partial<CartLine>): CartLine => ({ id: "x", itemId: "margherita", serving: 1, quantity: 1, modifierIds: [], note: "", ...over });

describe("the order being taken", () => {
  it("the plain + and − create, grow, shrink and remove one line", () => {
    let lines = nudgePlain([], "margherita", 1, 1);
    lines = nudgePlain(lines, "margherita", 1, 1);
    expect(lines).toHaveLength(1);
    expect(lines[0].quantity).toBe(2);
    lines = nudgePlain(lines, "margherita", 1, -1);
    expect(lines[0].quantity).toBe(1);
    lines = nudgePlain(lines, "margherita", 1, -1);
    expect(lines).toEqual([]);
    expect(nudgePlain([], "margherita", 1, -1)).toEqual([]);
  });

  it("never goes above 99", () => {
    const lines = nudgePlain([line({ quantity: 99 })], "margherita", 1, 1);
    expect(lines[0].quantity).toBe(99);
  });

  it("keeps servings apart", () => {
    let lines = nudgePlain([], "margherita", 1, 1);
    lines = nudgePlain(lines, "margherita", 2, 1);
    expect(lines).toHaveLength(2);
    expect(quantityOf(lines, "margherita", 1)).toBe(1);
    expect(quantityOf(lines, "margherita", 2)).toBe(1);
  });

  it("a pizza with a topping is a different line from a plain one", () => {
    let lines = nudgePlain([], "margherita", 1, 2);
    lines = addLine(lines, line({ id: "b", modifierIds: ["bufala"] }));
    expect(lines).toHaveLength(2);
    expect(quantityOf(lines, "margherita", 1)).toBe(3);
    // The plain − only touches the plain line.
    lines = nudgePlain(lines, "margherita", 1, -2);
    expect(lines).toHaveLength(1);
    expect(isPlain(lines[0])).toBe(false);
  });

  it("the same choice twice is one line, whatever order the options were ticked in", () => {
    let lines = addLine([], line({ id: "a", modifierIds: ["rucola", "bufala"] }));
    lines = addLine(lines, line({ id: "b", modifierIds: ["bufala", "rucola"] }));
    expect(lines).toHaveLength(1);
    expect(lines[0].quantity).toBe(2);
  });

  it("a note makes a line its own, and notes that differ only by spaces are the same", () => {
    let lines = addLine([], line({ id: "a", note: "senza pepe" }));
    lines = addLine(lines, line({ id: "b", note: "  senza pepe " }));
    lines = addLine(lines, line({ id: "c", note: "con ghiaccio" }));
    expect(lines).toHaveLength(2);
    expect(lines[0].quantity).toBe(2);
  });

  it("editing a line so it matches another joins them", () => {
    let lines = addLine([], line({ id: "a" }));
    lines = addLine(lines, line({ id: "b", modifierIds: ["bufala"], quantity: 2 }));
    lines = replaceLine(lines, "b", { modifierIds: [] });
    expect(lines).toHaveLength(1);
    expect(lines[0].quantity).toBe(3);
  });

  it("editing keeps the line where it was", () => {
    let lines = addLine([], line({ id: "a", itemId: "one" }));
    lines = addLine(lines, line({ id: "b", itemId: "two" }));
    lines = replaceLine(lines, "a", { note: "no basil" });
    expect(lines.map((l) => l.itemId)).toEqual(["one", "two"]);
    expect(lines[0].note).toBe("no basil");
  });

  it("removes a line", () => {
    const lines = removeLine([line({ id: "a" }), line({ id: "b", itemId: "other" })], "a");
    expect(lines.map((l) => l.id)).toEqual(["b"]);
  });

  it("prices a line as dish plus options, times the amount", () => {
    const l = line({ quantity: 2, modifierIds: ["bufala", "rucola", "free"] });
    expect(lineCents(l, 700, { bufala: 300, rucola: 150, free: 0 })).toBe(2 * (700 + 450));
    expect(lineCents(line({ quantity: 3 }), null, {})).toBe(0);
  });
});
