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

/** What part of the room is on screen, in metres: the top-left corner and the size of the window. */
export interface View {
  x: number;
  y: number;
  width: number;
  height: number;
}

/** Zoom limits, as a share of the room: 100% shows it all, 12.5% is eight times closer. */
const MIN_SHARE = 0.125;
const MARGIN = 0.5; // metres of breathing room around the room when fitted

/** The whole room, with a little margin. */
export function fitView(roomWidth: number, roomHeight: number): View {
  return { x: -MARGIN, y: -MARGIN, width: roomWidth + 2 * MARGIN, height: roomHeight + 2 * MARGIN };
}

/** Keeps the window from drifting away from the room: at least a quarter of it must stay in view. */
export function constrainView(view: View, roomWidth: number, roomHeight: number): View {
  const slackX = view.width * 0.75;
  const slackY = view.height * 0.75;
  return {
    ...view,
    x: clamp(view.x, -slackX, roomWidth - view.width + slackX),
    y: clamp(view.y, -slackY, roomHeight - view.height + slackY),
  };
}

/**
 * Zoom by a factor (above 1 zooms in) while keeping the point under the cursor still,
 * so the plan grows around what you are looking at. (px, py) is that point in metres.
 */
export function zoomAt(view: View, factor: number, px: number, py: number, roomWidth: number, roomHeight: number): View {
  const full = fitView(roomWidth, roomHeight);
  const width = clamp(view.width / factor, full.width * MIN_SHARE, full.width);
  const height = (width / full.width) * full.height;
  const scale = width / view.width;
  return constrainView(
    { x: px - (px - view.x) * scale, y: py - (py - view.y) * scale, width, height },
    roomWidth,
    roomHeight,
  );
}

/** Slide the window by a distance in metres. */
export function panBy(view: View, dx: number, dy: number, roomWidth: number, roomHeight: number): View {
  return constrainView({ ...view, x: view.x - dx, y: view.y - dy }, roomWidth, roomHeight);
}

/** How zoomed in the view is: 1 means the whole room, 2 means half of it. */
export function zoomLevel(view: View, roomWidth: number, roomHeight: number): number {
  return fitView(roomWidth, roomHeight).width / view.width;
}

/** A nice round length for the scale bar, about a fifth of what is on screen. */
export function scaleBarMetres(viewWidth: number): number {
  const target = viewWidth / 5;
  for (const step of [0.25, 0.5, 1, 2, 5, 10, 20, 50]) {
    if (step >= target * 0.7) return step;
  }
  return 50;
}

/** Where an element sits and how big it is, in metres, with its turn in degrees. */
export interface Geom {
  x: number;
  y: number;
  width: number;
  height: number;
  rotation: number;
}

/** The eight grab points around an element: n, s, e, w are the edge middles, the rest are corners. */
export type Handle = "n" | "s" | "e" | "w" | "ne" | "nw" | "se" | "sw";

export const HANDLES: Handle[] = ["nw", "n", "ne", "e", "se", "s", "sw", "w"];

/** Where a handle sits on an unrotated element centred on the origin: -1, 0 or 1 of the half-size on each axis. */
export function handleDirection(handle: Handle): { dx: -1 | 0 | 1; dy: -1 | 0 | 1 } {
  return {
    dx: handle.includes("e") ? 1 : handle.includes("w") ? -1 : 0,
    dy: handle.includes("s") ? 1 : handle.includes("n") ? -1 : 0,
  };
}

const RAD = Math.PI / 180;

/** Rounds a length to a whole number of grid steps, never below the minimum. */
function snapLength(length: number, step: number, min: number): number {
  const snapped = step > 0 ? Math.round(length / step) * step : length;
  return Math.max(min, tidy(snapped));
}

export interface ResizeOptions {
  /** Grid step in metres; 0 or less turns snapping off. */
  step: number;
  minWidth: number;
  minHeight: number;
  maxSize: number;
  /** Keep width and height equal (round and square tables, columns). */
  keepSquare: boolean;
}

/**
 * Dragging one handle of an element: the opposite edge or corner stays where it is and the grabbed side
 * follows the pointer. Works for turned elements too, by measuring the pointer along the element's own axes.
 */
export function resizeFromHandle(start: Geom, handle: Handle, pointer: { x: number; y: number }, options: ResizeOptions): Geom {
  const { dx, dy } = handleDirection(handle);
  const angle = start.rotation * RAD;
  const cos = Math.cos(angle);
  const sin = Math.sin(angle);
  // The pointer in the element's own frame: origin at its centre, axes turned with it.
  const rx = pointer.x - start.x;
  const ry = pointer.y - start.y;
  const lx = rx * cos + ry * sin;
  const ly = -rx * sin + ry * cos;

  let width = start.width;
  let height = start.height;
  if (dx !== 0) width = (lx - (-dx * start.width) / 2) * dx;
  if (dy !== 0) height = (ly - (-dy * start.height) / 2) * dy;
  width = Math.min(options.maxSize, snapLength(width, options.step, options.minWidth));
  height = Math.min(options.maxSize, snapLength(height, options.step, options.minHeight));

  if (options.keepSquare) {
    const side = dx !== 0 && dy !== 0 ? Math.max(width, height) : dx !== 0 ? width : height;
    width = side;
    height = side;
  }

  // The fixed side stays put, so the centre moves by half of what the size changed.
  const shiftX = dx === 0 ? 0 : dx * (width - start.width) / 2;
  const shiftY = dy === 0 ? 0 : dy * (height - start.height) / 2;
  return {
    x: tidy(start.x + shiftX * cos - shiftY * sin),
    y: tidy(start.y + shiftX * sin + shiftY * cos),
    width: tidy(width),
    height: tidy(height),
    rotation: start.rotation,
  };
}

/** Turning an element by dragging the knob above it: the knob follows the pointer around the centre. */
export function rotateToward(center: { x: number; y: number }, pointer: { x: number; y: number }, stepDegrees = 15): number {
  const degrees = Math.atan2(pointer.y - center.y, pointer.x - center.x) / RAD + 90;
  const snapped = stepDegrees > 0 ? Math.round(degrees / stepDegrees) * stepDegrees : degrees;
  return tidy(((snapped % 360) + 360) % 360);
}

/** New room size while one of its right or bottom handles is dragged: whole half-metres, within what the server accepts. */
export function resizeRoom(pointer: { x: number; y: number }, current: { width: number; height: number }, edge: "e" | "s" | "se"): { width: number; height: number } {
  const clampSize = (v: number) => clamp(Math.round(v * 2) / 2, 2, 200);
  return {
    width: edge === "s" ? current.width : clampSize(pointer.x),
    height: edge === "e" ? current.height : clampSize(pointer.y),
  };
}

/** Sensible smallest sizes: a table can be small but not tiny, a wall can be thin. */
export function minimumSize(kind: "table" | "obstacle"): { width: number; height: number } {
  return kind === "table" ? { width: 0.4, height: 0.4 } : { width: 0.1, height: 0.1 };
}
