import { describe, expect, it } from "vitest";
import { draftItems, fitWithin, selectionToRequests, type FloorDraft } from "./draft";

const room = { widthMeters: 12, heightMeters: 9 };

const draft: FloorDraft = {
  widthMeters: 12,
  heightMeters: 9,
  tables: [
    { shape: "ROUND", xMeters: 2, yMeters: 3, widthMeters: 1, heightMeters: 1, rotationDegrees: 0, seats: 4 },
    { shape: "RECTANGLE", xMeters: 20, yMeters: -1, widthMeters: 1.4, heightMeters: 0.9, rotationDegrees: 90, seats: 6 },
  ],
  elements: [
    { type: "BAR", xMeters: 6, yMeters: 8, widthMeters: 3, heightMeters: 0.7, rotationDegrees: 0 },
    { type: "WALL", xMeters: 6, yMeters: 12.5, widthMeters: 3, heightMeters: 0.2, rotationDegrees: 45 },
  ],
  warnings: [],
};

describe("fitWithin", () => {
  it("shrinks an image so its longest side is the maximum", () => {
    expect(fitWithin(4000, 3000, 1600)).toEqual({ width: 1600, height: 1200 });
    expect(fitWithin(3000, 4000, 1600)).toEqual({ width: 1200, height: 1600 });
  });

  it("never enlarges a small image", () => {
    expect(fitWithin(800, 600, 1600)).toEqual({ width: 800, height: 600 });
  });

  it("keeps the aspect ratio", () => {
    const { width, height } = fitWithin(4032, 3024, 1600);
    expect(width / height).toBeCloseTo(4032 / 3024, 2);
  });

  it("returns the exact size when the image already fits", () => {
    expect(fitWithin(1600, 1600, 1600)).toEqual({ width: 1600, height: 1600 });
    expect(fitWithin(1600, 900, 1600)).toEqual({ width: 1600, height: 900 });
  });

  it("returns whole pixels and never a side below 1", () => {
    expect(fitWithin(10000, 1, 1600)).toEqual({ width: 1600, height: 1 });
    const { width, height } = fitWithin(3001, 2001, 1600);
    expect(Number.isInteger(width)).toBe(true);
    expect(Number.isInteger(height)).toBe(true);
  });
});

describe("draftItems", () => {
  it("gives every item a stable id from its kind and index, tables first", () => {
    expect(draftItems(draft).map((i) => i.id)).toEqual(["t0", "t1", "e0", "e1"]);
  });

  it("keeps the original index when an element of an unknown type is left out", () => {
    const odd: FloorDraft = { ...draft, tables: [], elements: [{ ...draft.elements[0], type: "FOUNTAIN" }, draft.elements[1]] };
    expect(draftItems(odd).map((i) => i.id)).toEqual(["e1"]);
  });
});

describe("selectionToRequests", () => {
  it("returns nothing for an empty selection", () => {
    expect(selectionToRequests(draft, new Set(), room)).toEqual([]);
  });

  it("returns only the chosen items", () => {
    const requests = selectionToRequests(draft, new Set(["t1", "e0"]), room);
    expect(requests.map((r) => r.id)).toEqual(["t1", "e0"]);
  });

  it("splits tables and elements into the bodies of their own endpoints", () => {
    const requests = selectionToRequests(draft, new Set(["t0", "e0"]), room);
    expect(requests).toEqual([
      { id: "t0", kind: "table", body: { xMeters: 2, yMeters: 3, widthMeters: 1, heightMeters: 1, rotationDegrees: 0, shape: "ROUND", seats: 4 } },
      { id: "e0", kind: "obstacle", body: { xMeters: 6, yMeters: 8, widthMeters: 3, heightMeters: 0.7, rotationDegrees: 0, type: "BAR" } },
    ]);
  });

  it("keeps shape, size, rotation and seats of a table, and type, size and rotation of an element", () => {
    const [table, element] = selectionToRequests(draft, new Set(["t1", "e1"]), room);
    expect(table.kind).toBe("table");
    expect(table.body).toMatchObject({ shape: "RECTANGLE", widthMeters: 1.4, heightMeters: 0.9, rotationDegrees: 90, seats: 6 });
    expect(element.kind).toBe("obstacle");
    expect(element.body).toMatchObject({ type: "WALL", widthMeters: 3, heightMeters: 0.2, rotationDegrees: 45 });
  });

  it("does not number the tables, so the server does", () => {
    const [table] = selectionToRequests(draft, new Set(["t0"]), room);
    expect(table.body).not.toHaveProperty("number");
  });

  it("pulls centres that fall outside the room back inside", () => {
    const requests = selectionToRequests(draft, new Set(["t1", "e1"]), room);
    expect(requests[0].body).toMatchObject({ xMeters: 12, yMeters: 0 });
    expect(requests[1].body).toMatchObject({ xMeters: 6, yMeters: 9 });
  });
});
