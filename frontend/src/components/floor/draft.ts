import type { components } from "@/api/schema";
import { keepInRoom, type ObstacleType } from "./geometry";

export type FloorDraft = components["schemas"]["FloorDraft"];
export type TableRequest = components["schemas"]["TableRequest"];
export type ObstacleRequest = components["schemas"]["ObstacleRequest"];
export type TableShape = TableRequest["shape"];

/** The room a draft is added to: only its size matters here. */
export interface RoomSize {
  widthMeters: number;
  heightMeters: number;
}

/**
 * The size to shrink an image to so its longest side is at most `maxSide`, keeping the aspect ratio.
 * An image that is already small enough keeps its size (never enlarged). Both sides are whole pixels, at least 1.
 */
export function fitWithin(width: number, height: number, maxSide: number): { width: number; height: number } {
  const longest = Math.max(width, height);
  const scale = longest > maxSide && longest > 0 ? maxSide / longest : 1;
  return {
    width: Math.max(1, Math.round(width * scale)),
    height: Math.max(1, Math.round(height * scale)),
  };
}

const TABLE_SHAPES: Record<TableShape, true> = { ROUND: true, SQUARE: true, RECTANGLE: true };

// A Record over every type name, so the compiler complains here if the backend adds an element type.
const OBSTACLE_TYPES: Record<ObstacleType, true> = {
  WALL: true,
  COLUMN: true,
  DOOR: true,
  WINDOW: true,
  STAIRS: true,
  KITCHEN: true,
  TOILETS: true,
  BAR: true,
  ENTRANCE: true,
  STORAGE: true,
};

const isTableShape = (value: string): value is TableShape => Object.hasOwn(TABLE_SHAPES, value);
const isObstacleType = (value: string): value is ObstacleType => Object.hasOwn(OBSTACLE_TYPES, value);

interface DraftGeometry {
  /** Stable for one draft: "t" or "e" plus the index the server sent ("t0", "e3"). */
  id: string;
  x: number;
  y: number;
  width: number;
  height: number;
  rotation: number;
}

export type DraftItem =
  | (DraftGeometry & { kind: "table"; shape: TableShape; seats: number })
  | (DraftGeometry & { kind: "element"; type: ObstacleType });

/**
 * Every item of a draft in list order: the tables first, then the elements. Ids come from the position the server
 * sent them in, so they stay the same however the list is filtered. An unknown table shape is treated as a
 * rectangle; an element of an unknown type is left out, because nothing could be created from it.
 */
export function draftItems(draft: FloorDraft): DraftItem[] {
  const items: DraftItem[] = [];
  draft.tables.forEach((t, index) => {
    items.push({
      kind: "table",
      id: `t${index}`,
      shape: isTableShape(t.shape) ? t.shape : "RECTANGLE",
      seats: t.seats,
      x: t.xMeters,
      y: t.yMeters,
      width: t.widthMeters,
      height: t.heightMeters,
      rotation: t.rotationDegrees,
    });
  });
  draft.elements.forEach((e, index) => {
    if (!isObstacleType(e.type)) return;
    items.push({
      kind: "element",
      id: `e${index}`,
      type: e.type,
      x: e.xMeters,
      y: e.yMeters,
      width: e.widthMeters,
      height: e.heightMeters,
      rotation: e.rotationDegrees,
    });
  });
  return items;
}

/** One call to make against the existing create endpoints, with the body that endpoint takes. */
export type CreateRequest =
  | { id: string; kind: "table"; body: TableRequest }
  | { id: string; kind: "obstacle"; body: ObstacleRequest };

/**
 * The create requests for the ticked items, tables first and then elements, each in the order of the draft.
 * Centres are pulled inside the room; shape, size, rotation and seats are kept as the draft has them.
 * A table gets no number here, so the server numbers it (in the order the calls are made).
 */
export function selectionToRequests(draft: FloorDraft, chosenIds: ReadonlySet<string>, room: RoomSize): CreateRequest[] {
  const requests: CreateRequest[] = [];
  for (const item of draftItems(draft)) {
    if (!chosenIds.has(item.id)) continue;
    const { x, y } = keepInRoom(item.x, item.y, room.widthMeters, room.heightMeters);
    const common = { xMeters: x, yMeters: y, widthMeters: item.width, heightMeters: item.height, rotationDegrees: item.rotation };
    if (item.kind === "table") {
      requests.push({ id: item.id, kind: "table", body: { ...common, shape: item.shape, seats: item.seats } });
    } else {
      requests.push({ id: item.id, kind: "obstacle", body: { ...common, type: item.type } });
    }
  }
  return requests;
}
