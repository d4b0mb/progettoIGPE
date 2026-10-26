import { describe, expect, it } from "vitest";
import { clamp, defaultObstacleSize, dragTarget, HANDLES, keepInRoom, moveByKey, resizeFromHandle, resizeRoom, rotateToward, snap, tidy, type Geom, type ResizeOptions } from "./geometry";

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

import { constrainView, fitView, panBy, scaleBarMetres, zoomAt, zoomLevel } from "./geometry";

describe("camera: zoom and pan", () => {
  const W = 12;
  const H = 9;

  it("starts by showing the whole room with a margin", () => {
    expect(fitView(W, H)).toEqual({ x: -0.5, y: -0.5, width: 13, height: 10 });
    expect(zoomLevel(fitView(W, H), W, H)).toBe(1);
  });

  it("zooms in around the point under the cursor, which stays where it is", () => {
    const start = fitView(W, H);
    const next = zoomAt(start, 2, 6, 4.5, W, H); // the room's centre
    expect(next.width).toBeCloseTo(start.width / 2);
    // The centre point keeps the same relative position inside the window.
    expect((6 - next.x) / next.width).toBeCloseTo((6 - start.x) / start.width);
    expect((4.5 - next.y) / next.height).toBeCloseTo((4.5 - start.y) / start.height);
  });

  it("keeps the window's shape the same as the room's when zooming", () => {
    const next = zoomAt(fitView(W, H), 3, 2, 2, W, H);
    expect(next.width / next.height).toBeCloseTo(13 / 10);
  });

  it("cannot zoom out past the whole room or in past eight times", () => {
    const start = fitView(W, H);
    expect(zoomAt(start, 0.2, 6, 4.5, W, H).width).toBe(start.width);
    expect(zoomLevel(zoomAt(start, 100, 6, 4.5, W, H), W, H)).toBeCloseTo(8);
  });

  it("zooming in and back out returns to the same view", () => {
    const start = fitView(W, H);
    const back = zoomAt(zoomAt(start, 2, 3, 3, W, H), 0.5, 3, 3, W, H);
    expect(back.width).toBeCloseTo(start.width);
    expect(back.x).toBeCloseTo(start.x);
    expect(back.y).toBeCloseTo(start.y);
  });

  it("pans by a distance, and dragging right moves the plan right", () => {
    const zoomed = zoomAt(fitView(W, H), 2, 6, 4.5, W, H);
    const moved = panBy(zoomed, 1, 0.5, W, H);
    expect(moved.x).toBeCloseTo(zoomed.x - 1);
    expect(moved.y).toBeCloseTo(zoomed.y - 0.5);
  });

  it("will not let the plan be dragged out of sight", () => {
    const zoomed = zoomAt(fitView(W, H), 2, 6, 4.5, W, H);
    const far = panBy(zoomed, -500, 500, W, H);
    expect(far.x).toBeLessThanOrEqual(W);
    expect(far.x + far.width).toBeGreaterThan(0);
    expect(constrainView(far, W, H)).toEqual(far);
  });

  it("picks a round scale-bar length", () => {
    expect(scaleBarMetres(13)).toBe(2);
    expect(scaleBarMetres(40)).toBe(10);
    expect(scaleBarMetres(1.5)).toBe(0.25);
  });
});

describe("resizing by dragging a handle", () => {
  const box: Geom = { x: 5, y: 4, width: 2, height: 1, rotation: 0 };
  const free: ResizeOptions = { step: 0, minWidth: 0.4, minHeight: 0.4, maxSize: 50, keepSquare: false };

  it("stretches to the right while the left edge stays put", () => {
    const next = resizeFromHandle(box, "e", { x: 8, y: 4 }, free);
    expect(next).toMatchObject({ width: 4, height: 1 });
    expect(next.x - next.width / 2).toBeCloseTo(4); // left edge was at 5 - 1 = 4
  });

  it("stretches to the left while the right edge stays put", () => {
    const next = resizeFromHandle(box, "w", { x: 3, y: 4 }, free);
    expect(next.width).toBe(3);
    expect(next.x + next.width / 2).toBeCloseTo(6); // right edge was at 6
  });

  it("a corner changes both sides and keeps the opposite corner fixed", () => {
    const next = resizeFromHandle(box, "se", { x: 8, y: 7 }, free);
    expect(next.width).toBe(4); // from the fixed left edge at x=4 to the pointer at x=8
    expect(next.height).toBe(3.5); // from the fixed top edge at y=3.5 to the pointer at y=7
    expect(next.x - next.width / 2).toBeCloseTo(4);
    expect(next.y - next.height / 2).toBeCloseTo(3.5);
  });

  it("an edge handle leaves the other direction alone", () => {
    const next = resizeFromHandle(box, "n", { x: 20, y: 1.5 }, free);
    expect(next.width).toBe(2);
    expect(next.x).toBe(5);
    expect(next.height).toBe(3);
  });

  it("never gets smaller than the minimum, even when dragged past the opposite side", () => {
    const next = resizeFromHandle(box, "e", { x: 0, y: 4 }, free);
    expect(next.width).toBe(0.4);
  });

  it("snaps the size to whole grid steps", () => {
    const next = resizeFromHandle(box, "e", { x: 6.6, y: 4 }, { ...free, step: 0.5 });
    expect(next.width).toBe(2.5); // 2.6 m rounds to the nearest half metre
  });

  it("keeps round and square things square", () => {
    const next = resizeFromHandle({ ...box, width: 1, height: 1 }, "se", { x: 8, y: 5 }, { ...free, keepSquare: true });
    expect(next.width).toBe(next.height);
    expect(next.width).toBeGreaterThan(1);
  });

  it("follows the element's own axes when it is turned", () => {
    // Turned a quarter: its "east" edge now points down the screen.
    const turned: Geom = { x: 5, y: 5, width: 2, height: 1, rotation: 90 };
    const next = resizeFromHandle(turned, "e", { x: 5, y: 9 }, free);
    expect(next.width).toBe(5); // from the fixed edge at y=4 down to y=9
    expect(next.x).toBeCloseTo(5);
    expect(next.y).toBeCloseTo(6.5);
  });

  it("never exceeds the biggest size", () => {
    expect(resizeFromHandle(box, "e", { x: 500, y: 4 }, free).width).toBe(50);
  });

  it("has eight handles", () => {
    expect(new Set(HANDLES).size).toBe(8);
  });
});

describe("turning by dragging the knob", () => {
  it("points up for no turn and snaps to 15 degrees", () => {
    expect(rotateToward({ x: 0, y: 0 }, { x: 0, y: -5 })).toBe(0);
    expect(rotateToward({ x: 0, y: 0 }, { x: 5, y: 0 })).toBe(90);
    expect(rotateToward({ x: 0, y: 0 }, { x: 0, y: 5 })).toBe(180);
    expect(rotateToward({ x: 0, y: 0 }, { x: -5, y: 0 })).toBe(270);
    expect(rotateToward({ x: 0, y: 0 }, { x: 5, y: -1.2 })).toBe(75);
  });
});

describe("stretching the room", () => {
  it("moves only the grabbed edges, in half metres, within the allowed range", () => {
    expect(resizeRoom({ x: 13.3, y: 20 }, { width: 12, height: 9 }, "e")).toEqual({ width: 13.5, height: 9 });
    expect(resizeRoom({ x: 13.3, y: 10.2 }, { width: 12, height: 9 }, "se")).toEqual({ width: 13.5, height: 10 });
    expect(resizeRoom({ x: 0, y: 0 }, { width: 12, height: 9 }, "se")).toEqual({ width: 2, height: 2 });
    expect(resizeRoom({ x: 900, y: 900 }, { width: 12, height: 9 }, "se")).toEqual({ width: 200, height: 200 });
  });
});
