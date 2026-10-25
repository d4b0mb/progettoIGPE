import { describe, expect, it } from "vitest";
import { groupBySection } from "./sections";

describe("menu sections", () => {
  it("groups by section and keeps the order the sections first appear in", () => {
    const items = [
      { id: 1, section: "Birre" },
      { id: 2, section: "Vini" },
      { id: 3, section: "Birre" },
      { id: 4, section: "" },
    ];
    expect(groupBySection(items).map((g) => [g.section, g.items.map((i) => i.id)])).toEqual([
      ["Birre", [1, 3]],
      ["Vini", [2]],
      ["", [4]],
    ]);
  });

  it("returns nothing for an empty list", () => {
    expect(groupBySection([])).toEqual([]);
  });
});
