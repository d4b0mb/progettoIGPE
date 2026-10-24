import type { components } from "@/api/schema";

export type ObstacleType = components["schemas"]["Obstacle"]["type"];

/** Snap a coordinate to the nearest grid line. A step of 0 or less means no snapping. */
export function snap(value: number, step: number): number {
  if (step <= 0) return value;
  return Math.round(value / step) * step;
}

export function clamp(value: number, min: number, max: number): number {
  return Math.min(Math.max(value, min), max);
}

/** Round away floating point dust (0.1 + 0.2) so saved positions stay tidy. */
export function tidy(value: number): number {
  return Math.round(value * 1000) / 1000;
}

/** Keeps an element's centre inside the room. */
export function keepInRoom(x: number, y: number, roomWidth: number, roomHeight: number): { x: number; y: number } {
  return { x: tidy(clamp(x, 0, roomWidth)), y: tidy(clamp(y, 0, roomHeight)) };
}

/** Where an element lands when dragged: pointer position minus the grab offset, snapped, then kept in the room. */
export function dragTarget(
  pointer: { x: number; y: number },
  grabOffset: { x: number; y: number },
  step: number,
  roomWidth: number,
  roomHeight: number,
): { x: number; y: number } {
  return keepInRoom(snap(pointer.x - grabOffset.x, step), snap(pointer.y - grabOffset.y, step), roomWidth, roomHeight);
}

const ARROWS: Record<string, [number, number]> = {
  ArrowLeft: [-1, 0],
  ArrowRight: [1, 0],
  ArrowUp: [0, -1],
  ArrowDown: [0, 1],
};

/** Keyboard movement: one grid step per arrow press. Returns null for any other key. */
export function moveByKey(
  key: string,
  x: number,
  y: number,
  step: number,
  roomWidth: number,
  roomHeight: number,
): { x: number; y: number } | null {
  const dir = ARROWS[key];
  if (!dir) return null;
  const size = step > 0 ? step : 0.25;
  return keepInRoom(x + dir[0] * size, y + dir[1] * size, roomWidth, roomHeight);
}

/** A sensible starting size, in metres, for each kind of non-bookable element. */
export function defaultObstacleSize(type: ObstacleType): { width: number; height: number } {
  switch (type) {
    case "WALL":
      return { width: 3, height: 0.2 };
    case "COLUMN":
      return { width: 0.5, height: 0.5 };
    case "DOOR":
    case "WINDOW":
      return { width: 1, height: 0.15 };
    case "STAIRS":
      return { width: 1.2, height: 2.5 };
    case "BAR":
      return { width: 3, height: 0.7 };
    case "KITCHEN":
      return { width: 4, height: 3 };
    case "TOILETS":
      return { width: 2, height: 2 };
    case "ENTRANCE":
      return { width: 1.5, height: 0.3 };
    case "STORAGE":
      return { width: 2, height: 1.5 };
  }
}
