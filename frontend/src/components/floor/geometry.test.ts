import { describe, expect, it } from "vitest";
import { clamp, defaultObstacleSize, dragTarget, keepInRoom, moveByKey, snap, tidy } from "./geometry";

describe("floor plan geometry", () => {
  it("snaps to the grid", () => {
    expect(snap(1.13, 0.25)).toBe(1.25);
    expect(snap(1.12, 0.25)).toBe(1);
    expect(snap(2.4, 0)).toBe(2.4);
  });

  it("clamps a value to a range", () => {
    expect(clamp(-3, 0, 10)).toBe(0);
    expect(clamp(12, 0, 10)).toBe(10);
    expect(clamp(5, 0, 10)).toBe(5);
  });

  it("removes floating point dust", () => {
    expect(tidy(0.1 + 0.2)).toBe(0.3);
    expect(tidy(1.23456)).toBe(1.235);
  });

  it("keeps the centre of an element inside the room", () => {
    expect(keepInRoom(-1, 4, 12, 9)).toEqual({ x: 0, y: 4 });
    expect(keepInRoom(13, 10, 12, 9)).toEqual({ x: 12, y: 9 });
  });

  it("drags an element by the point where it was grabbed, snapped and kept inside", () => {
    // Grabbed 0.1 m right of the centre; pointer is now at 3.3: centre 3.2 snaps to 3.25.
    expect(dragTarget({ x: 3.3, y: 2 }, { x: 0.1, y: 0 }, 0.25, 12, 9)).toEqual({ x: 3.25, y: 2 });
    // Dragged far outside the room, it stops at the wall.
    expect(dragTarget({ x: 50, y: -4 }, { x: 0, y: 0 }, 0.25, 12, 9)).toEqual({ x: 12, y: 0 });
  });

  it("moves one grid step per arrow key and ignores other keys", () => {
    expect(moveByKey("ArrowRight", 2, 2, 0.25, 12, 9)).toEqual({ x: 2.25, y: 2 });
    expect(moveByKey("ArrowUp", 2, 2, 0.25, 12, 9)).toEqual({ x: 2, y: 1.75 });
    expect(moveByKey("ArrowLeft", 0, 2, 0.25, 12, 9)).toEqual({ x: 0, y: 2 });
    expect(moveByKey("a", 2, 2, 0.25, 12, 9)).toBeNull();
  });

  it("gives every element type a positive default size", () => {
    for (const type of ["WALL", "COLUMN", "DOOR", "WINDOW", "STAIRS", "KITCHEN", "TOILETS", "BAR", "ENTRANCE", "STORAGE"] as const) {
      const size = defaultObstacleSize(type);
      expect(size.width).toBeGreaterThan(0);
      expect(size.height).toBeGreaterThan(0);
    }
  });
});
