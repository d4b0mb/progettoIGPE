"use client";

import { useTranslations } from "next-intl";
import { useEffect, useRef, useState, type KeyboardEvent, type PointerEvent as ReactPointerEvent } from "react";
import type { components } from "@/api/schema";
import { Icon } from "@/components/ui/Icon";
import {
  dragTarget,
  fitView,
  handleDirection,
  HANDLES,
  minimumSize,
  moveByKey,
  panBy,
  resizeFromHandle,
  resizeRoom,
  rotateToward,
  scaleBarMetres,
  zoomAt,
  zoomLevel,
  type Geom,
  type Handle,
  type View,
} from "./geometry";

type Layout = components["schemas"]["Layout"];
type Table = components["schemas"]["DiningTable"];
type Obstacle = components["schemas"]["Obstacle"];
type Live = components["schemas"]["TableView"]["liveStatus"];
type Kind = "table" | "obstacle";

export type Selection = { kind: Kind; id: string } | null;

/** Colours of a table by what is happening at it right now. Text and outline differ in lightness, not just hue. */
const LIVE_STYLE: Record<Live, { fill: string; stroke: string; text: string; dash?: string }> = {
  FREE: { fill: "var(--color-primary-soft)", stroke: "var(--color-primary)", text: "var(--color-ink)" },
  RESERVED: { fill: "var(--color-warn-soft)", stroke: "var(--plan-reserved-line)", text: "var(--color-warn-ink)" },
  OCCUPIED: { fill: "var(--color-primary)", stroke: "var(--color-primary-strong)", text: "var(--color-on-primary)" },
  TO_CLEAN: { fill: "var(--color-info-soft)", stroke: "var(--plan-selected)", text: "var(--color-info-ink)" },
  OUT_OF_SERVICE: { fill: "var(--plan-obstacle-fill)", stroke: "var(--plan-room-line)", text: "var(--plan-text)", dash: "0.12 0.08" },
};

const OBSTACLE_FILL = "var(--plan-obstacle-fill)";
const OBSTACLE_STROKE = "var(--plan-obstacle-line)";
const SELECTED = "var(--plan-selected)";


type Gesture =
  | { type: "move"; kind: Kind; id: string; grabX: number; grabY: number; geom: Geom; moved: boolean }
  | { type: "resize"; kind: Kind; id: string; handle: Handle; start: Geom; geom: Geom; changed: boolean }
  | { type: "rotate"; kind: Kind; id: string; start: Geom; geom: Geom; changed: boolean }
  | { type: "room"; edge: "e" | "s" | "se"; width: number; height: number; changed: boolean };

interface Pan {
  pointerId: number;
  lastX: number;
  lastY: number;
}

const HANDLE_CURSOR: Record<Handle, string> = { n: "ns-resize", s: "ns-resize", e: "ew-resize", w: "ew-resize", ne: "nesw-resize", sw: "nesw-resize", nw: "nwse-resize", se: "nwse-resize" };

const geomOf = (el: Table | Obstacle): Geom => ({ x: el.xMeters, y: el.yMeters, width: el.widthMeters, height: el.heightMeters, rotation: el.rotationDegrees });

/** Nice number for a label: 2.5 not 2.500. */
const fmt = (n: number) => String(Math.round(n * 100) / 100);

/**
 * The room drawn to scale. Coordinates are in metres. The picture always fits the screen
 * height; zoom in with the buttons or Ctrl + scroll, and drag the empty floor to move around.
 * Editors work directly on the drawing, with mouse or finger: drag an element to move it, drag the
 * handles on its edges and corners to resize it, drag the knob above it to turn it, and drag the
 * room's right and bottom edges to stretch the room. The arrow keys nudge the selected element.
 */
export function FloorCanvas({
  layout,
  selection,
  editable,
  snapOn,
  onSelect,
  onGeometry,
  onRoomSize,
  onViewCenter,
  fill = false,
  onToggleFill,
  bookings,
}: {
  layout: Layout;
  selection: Selection;
  editable: boolean;
  snapOn: boolean;
  onSelect: (selection: Selection) => void;
  onGeometry: (kind: Kind, id: string, geom: Geom) => void;
  onRoomSize: (width: number, height: number) => void;
  /** Tells the screen which spot is in the middle of what is shown, so new objects appear where you are looking. */
  onViewCenter?: (center: { x: number; y: number }) => void;
  /** Use the whole height of the window (expanded view) instead of leaving room for the page around it. */
  fill?: boolean;
  onToggleFill?: () => void;
  /** The time of the next booking at each table (by table id), shown on the table. */
  bookings?: Record<string, string>;
}) {
  const t = useTranslations("floor");
  const te = useTranslations("elements");
  const svgRef = useRef<SVGSVGElement>(null);
  const { room } = layout;
  const step = snapOn ? room.gridStepMeters : 0;

  const [gesture, setGesture] = useState<Gesture | null>(null);
  // While the room is being stretched it is drawn at its new size before anything is saved.
  const W = gesture?.type === "room" ? gesture.width : room.widthMeters;
  const H = gesture?.type === "room" ? gesture.height : room.heightMeters;
  const [view, setView] = useState<View>(() => fitView(room.widthMeters, room.heightMeters));
  const [roomSize, setRoomSize] = useState({ w: room.widthMeters, h: room.heightMeters });
  const [pan, setPan] = useState<Pan | null>(null);
  const [pxPerMeter, setPxPerMeter] = useState(40);

  // When the room has a new saved size, show all of it again.
  if (roomSize.w !== room.widthMeters || roomSize.h !== room.heightMeters) {
    setRoomSize({ w: room.widthMeters, h: room.heightMeters });
    setView(fitView(room.widthMeters, room.heightMeters));
  }

  // Ctrl + scroll zooms the plan only after the plan was clicked; otherwise the browser's own zoom keeps working.
  const [active, setActive] = useState(false);
  const activeRef = useRef(false);
  const zoomRef = useRef<(deltaY: number, clientX: number, clientY: number) => void>(() => {});
  // Keep the wheel listener (set up once below) pointed at the latest state.
  useEffect(() => {
    activeRef.current = active;
    zoomRef.current = (deltaY, clientX, clientY) => zoomBy(deltaY < 0 ? 1.15 : 1 / 1.15, toMeters(clientX, clientY) ?? undefined);
  });

  useEffect(() => {
    const svg = svgRef.current;
    if (!svg) return;
    // A native listener: React's onWheel is passive, so it cannot stop the browser from zooming the page.
    const onWheel = (event: WheelEvent) => {
      if ((!event.ctrlKey && !event.metaKey) || !activeRef.current) return;
      event.preventDefault();
      zoomRef.current(event.deltaY, event.clientX, event.clientY);
    };
    const onOutside = (event: PointerEvent) => {
      if (!svg.contains(event.target as Node)) setActive(false);
    };
    const onEscape = (event: globalThis.KeyboardEvent) => {
      if (event.key === "Escape") setActive(false);
    };
    svg.addEventListener("wheel", onWheel, { passive: false });
    document.addEventListener("pointerdown", onOutside);
    document.addEventListener("keydown", onEscape);
    return () => {
      svg.removeEventListener("wheel", onWheel);
      document.removeEventListener("pointerdown", onOutside);
      document.removeEventListener("keydown", onEscape);
    };
  }, []);

  // Handles are drawn a fixed size on screen, so we need to know how many pixels a metre is right now.
  useEffect(() => {
    const svg = svgRef.current;
    if (!svg) return;
    const measure = () => setPxPerMeter(Math.max(1, svg.getBoundingClientRect().width / view.width));
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(svg);
    return () => observer.disconnect();
  }, [view.width]);

  useEffect(() => {
    onViewCenter?.({ x: view.x + view.width / 2, y: view.y + view.height / 2 });
  }, [view, onViewCenter]);

  /** Pixel position on screen to metres in the room, using the SVG's own transform. */
  function toMeters(clientX: number, clientY: number): { x: number; y: number } | null {
    const svg = svgRef.current;
    const matrix = svg?.getScreenCTM();
    if (!svg || !matrix) return null;
    const point = svg.createSVGPoint();
    point.x = clientX;
    point.y = clientY;
    const local = point.matrixTransform(matrix.inverse());
    return { x: local.x, y: local.y };
  }

  function zoomBy(factor: number, centre?: { x: number; y: number }) {
    setView((v) => zoomAt(v, factor, centre?.x ?? v.x + v.width / 2, centre?.y ?? v.y + v.height / 2, W, H));
  }

  // --- panning the empty floor (mouse and pen; on a touch screen one finger scrolls the page instead)
  function onBackgroundDown(event: ReactPointerEvent<SVGSVGElement>) {
    const background = event.target === event.currentTarget || (event.target as Element).id === "floor-background";
    if (!background) return;
    onSelect(null);
    if (event.pointerType === "touch") return;
    event.currentTarget.setPointerCapture(event.pointerId);
    setPan({ pointerId: event.pointerId, lastX: event.clientX, lastY: event.clientY });
  }

  function onBackgroundMove(event: ReactPointerEvent<SVGSVGElement>) {
    if (!pan || pan.pointerId !== event.pointerId) return;
    const here = toMeters(event.clientX, event.clientY);
    const before = toMeters(pan.lastX, pan.lastY);
    if (!here || !before) return;
    setView((v) => panBy(v, here.x - before.x, here.y - before.y, W, H));
    setPan({ ...pan, lastX: event.clientX, lastY: event.clientY });
  }

  function onBackgroundUp(event: ReactPointerEvent<SVGSVGElement>) {
    if (pan && pan.pointerId === event.pointerId) {
      event.currentTarget.releasePointerCapture(event.pointerId);
      setPan(null);
    }
  }

  // --- moving an element
  function onElementDown(event: ReactPointerEvent<SVGGElement>, kind: Kind, el: Table | Obstacle) {
    event.stopPropagation();
    onSelect({ kind, id: el.id });
    if (!editable) return;
    const p = toMeters(event.clientX, event.clientY);
    if (!p) return;
    event.currentTarget.setPointerCapture(event.pointerId);
    setGesture({ type: "move", kind, id: el.id, grabX: p.x - el.xMeters, grabY: p.y - el.yMeters, geom: geomOf(el), moved: false });
  }

  function onElementMove(event: ReactPointerEvent<SVGGElement>) {
    if (gesture?.type !== "move") return;
    const p = toMeters(event.clientX, event.clientY);
    if (!p) return;
    const next = dragTarget(p, { x: gesture.grabX, y: gesture.grabY }, step, W, H);
    setGesture({ ...gesture, geom: { ...gesture.geom, ...next }, moved: gesture.moved || next.x !== gesture.geom.x || next.y !== gesture.geom.y });
  }

  function finish(event: ReactPointerEvent<Element>) {
    if (!gesture) return;
    if (event.currentTarget.hasPointerCapture(event.pointerId)) event.currentTarget.releasePointerCapture(event.pointerId);
    if (gesture.type === "room") {
      if (gesture.changed) onRoomSize(gesture.width, gesture.height);
    } else if (gesture.type === "move" ? gesture.moved : gesture.changed) {
      onGeometry(gesture.kind, gesture.id, gesture.geom);
    }
    setGesture(null);
  }

  // --- resizing and turning the selected element
  function startResize(event: ReactPointerEvent<SVGElement>, kind: Kind, el: Table | Obstacle, handle: Handle) {
    event.stopPropagation();
    event.currentTarget.setPointerCapture(event.pointerId);
    setGesture({ type: "resize", kind, id: el.id, handle, start: geomOf(el), geom: geomOf(el), changed: false });
  }

  function moveResize(event: ReactPointerEvent<SVGElement>, kind: Kind, el: Table | Obstacle) {
    if (gesture?.type !== "resize" || gesture.id !== el.id) return;
    const p = toMeters(event.clientX, event.clientY);
    if (!p) return;
    const min = minimumSize(kind);
    const square = kind === "table" ? (el as Table).shape !== "RECTANGLE" : (el as Obstacle).type === "COLUMN";
    const geom = resizeFromHandle(gesture.start, gesture.handle, p, { step, minWidth: min.width, minHeight: min.height, maxSize: 50, keepSquare: square });
    setGesture({ ...gesture, geom, changed: gesture.changed || geom.width !== gesture.start.width || geom.height !== gesture.start.height });
  }

  function startRotate(event: ReactPointerEvent<SVGElement>, kind: Kind, el: Table | Obstacle) {
    event.stopPropagation();
    event.currentTarget.setPointerCapture(event.pointerId);
    setGesture({ type: "rotate", kind, id: el.id, start: geomOf(el), geom: geomOf(el), changed: false });
  }

  function moveRotate(event: ReactPointerEvent<SVGElement>, el: Table | Obstacle) {
    if (gesture?.type !== "rotate" || gesture.id !== el.id) return;
    const p = toMeters(event.clientX, event.clientY);
    if (!p) return;
    const rotation = rotateToward({ x: gesture.start.x, y: gesture.start.y }, p, step > 0 ? 15 : 1);
    setGesture({ ...gesture, geom: { ...gesture.start, rotation }, changed: rotation !== gesture.start.rotation });
  }

  // --- stretching the room
  function startRoom(event: ReactPointerEvent<SVGElement>, edge: "e" | "s" | "se") {
    event.stopPropagation();
    event.currentTarget.setPointerCapture(event.pointerId);
    onSelect(null);
    setGesture({ type: "room", edge, width: room.widthMeters, height: room.heightMeters, changed: false });
  }

  function moveRoom(event: ReactPointerEvent<SVGElement>) {
    if (gesture?.type !== "room") return;
    const p = toMeters(event.clientX, event.clientY);
    if (!p) return;
    const size = resizeRoom(p, { width: room.widthMeters, height: room.heightMeters }, gesture.edge);
    setGesture({ ...gesture, ...size, changed: size.width !== room.widthMeters || size.height !== room.heightMeters });
  }

  function onElementKey(event: KeyboardEvent<SVGGElement>, kind: Kind, el: Table | Obstacle) {
    if (event.key === "Enter" || event.key === " ") {
      event.preventDefault();
      onSelect({ kind, id: el.id });
      return;
    }
    if (!editable) return;
    const next = moveByKey(event.key, el.xMeters, el.yMeters, room.gridStepMeters, W, H);
    if (next) {
      event.preventDefault();
      onSelect({ kind, id: el.id });
      onGeometry(kind, el.id, { ...geomOf(el), ...next });
    }
  }

  const geometry = (kind: Kind, el: Table | Obstacle): Geom =>
    gesture && gesture.type !== "room" && gesture.kind === kind && gesture.id === el.id ? gesture.geom : geomOf(el);
  const isSelected = (kind: Kind, id: string) => selection?.kind === kind && selection.id === id;
  const elementStyle = { cursor: editable ? "grab" : "pointer", touchAction: editable ? ("none" as const) : ("auto" as const), outline: "none" };
  const label = 0.3;
  const bar = scaleBarMetres(view.width);
  const level = zoomLevel(view, W, H);
  const px = (n: number) => n / pxPerMeter; // screen pixels to metres at the current zoom

  /** The grab points around the selected element: eight resize handles and a turning knob. */
  function renderHandles(kind: Kind, el: Table | Obstacle) {
    const g = geometry(kind, el);
    const knobDistance = px(34);
    // A small item on screen would be buried under eight handles: keep only the corners, with smaller grab areas.
    const sizeOnScreen = Math.min(g.width, g.height) * pxPerMeter;
    const compact = sizeOnScreen < 64;
    const grab = compact ? Math.max(11, sizeOnScreen * 0.45) : 22;
    return (
      <g transform={`translate(${g.x} ${g.y}) rotate(${g.rotation})`}>
        <rect x={-g.width / 2} y={-g.height / 2} width={g.width} height={g.height} fill="none" stroke={SELECTED} strokeWidth={px(1.5)} strokeDasharray={`${px(5)} ${px(4)}`} pointerEvents="none" />
        {HANDLES.filter((handle) => !compact || handle.length === 2).map((handle) => {
          const { dx, dy } = handleDirection(handle);
          return (
            <g
              key={handle}
              data-handle={handle}
              transform={`translate(${(dx * g.width) / 2} ${(dy * g.height) / 2})`}
              style={{ cursor: HANDLE_CURSOR[handle], touchAction: "none" }}
              onPointerDown={(e) => startResize(e, kind, el, handle)}
              onPointerMove={(e) => moveResize(e, kind, el)}
              onPointerUp={finish}
              onPointerCancel={finish}
            >
              <circle r={px(grab)} fill="transparent" />
              <rect x={-px(7)} y={-px(7)} width={px(14)} height={px(14)} rx={px(3)} fill="var(--color-surface)" stroke={SELECTED} strokeWidth={px(2)} />
            </g>
          );
        })}
        <g
          data-handle="rotate"
          transform={`translate(0 ${-g.height / 2 - knobDistance})`}
          style={{ cursor: "grab", touchAction: "none" }}
          onPointerDown={(e) => startRotate(e, kind, el)}
          onPointerMove={(e) => moveRotate(e, el)}
          onPointerUp={finish}
          onPointerCancel={finish}
        >
          <line x1="0" y1="0" x2="0" y2={knobDistance - px(9)} stroke={SELECTED} strokeWidth={px(1.5)} pointerEvents="none" />
          <circle r={px(22)} fill="transparent" />
          <circle r={px(9)} fill={SELECTED} stroke="var(--color-surface)" strokeWidth={px(2)} />
        </g>
      </g>
    );
  }

  /** A small readout while something is being resized or turned. */
  function renderReadout() {
    if (!gesture || gesture.type === "move") return null;
    if (gesture.type === "room") {
      return (
        <text x={W} y={H + px(26)} textAnchor="end" fontSize={px(14)} fontWeight="800" fill="var(--color-ink)" stroke="var(--color-surface)" strokeWidth={px(4)} paintOrder="stroke" pointerEvents="none">
          {fmt(W)} × {fmt(H)} m
        </text>
      );
    }
    const g = gesture.geom;
    const text = gesture.type === "rotate" ? `${Math.round(g.rotation)}°` : `${fmt(g.width)} × ${fmt(g.height)} m`;
    const reach = Math.max(g.width, g.height) / 2 + px(60);
    return (
      <text x={g.x} y={g.y - reach} textAnchor="middle" fontSize={px(14)} fontWeight="800" fill="var(--color-ink)" stroke="var(--color-surface)" strokeWidth={px(4)} paintOrder="stroke" pointerEvents="none">
        {text}
      </text>
    );
  }

  const selectedTable = selection?.kind === "table" ? layout.tables.find((v) => v.table.id === selection.id)?.table : undefined;
  const selectedObstacle = selection?.kind === "obstacle" ? layout.obstacles.find((o) => o.id === selection.id) : undefined;

  return (
    <div className="flex flex-col gap-2">
      {/* The controls sit above the drawing, not on it, so they never cover the grid or the tables. */}
      <div className="flex items-center justify-between gap-2">
        {onToggleFill ? (
          <button
            type="button"
            aria-pressed={fill}
            onClick={onToggleFill}
            className="flex min-h-11 min-w-11 items-center justify-center rounded-xl border border-line-strong bg-surface shadow-sm hover:bg-canvas"
          >
            <Icon name={fill ? "collapse" : "expand"} />
            <span className="sr-only">{fill ? t("collapsePlan") : t("expandPlan")}</span>
          </button>
        ) : (
          <span />
        )}
        <div className="flex overflow-hidden rounded-xl border border-line-strong bg-surface shadow-sm" role="group" aria-label={t("fit")}>
          <button type="button" aria-label={t("zoomOut")} onClick={() => zoomBy(1 / 1.4)} className="flex min-h-11 min-w-11 items-center justify-center text-xl font-bold hover:bg-canvas">
            −
          </button>
          <button
            type="button"
            aria-label={t("fit")}
            title={t("fit")}
            disabled={level === 1 && view.x === fitView(W, H).x}
            onClick={() => setView(fitView(W, H))}
            className="flex min-h-11 min-w-16 items-center justify-center border-x border-line text-xs font-extrabold hover:bg-canvas disabled:opacity-40"
          >
            {Math.round(level * 100)}%
          </button>
          <button type="button" aria-label={t("zoomIn")} onClick={() => zoomBy(1.4)} className="flex min-h-11 min-w-11 items-center justify-center text-xl font-bold hover:bg-canvas">
            +
          </button>
        </div>
      </div>
      <svg
        ref={svgRef}
        viewBox={`${view.x} ${view.y} ${view.width} ${view.height}`}
        role="group"
        aria-label={room.name}
        className={`block w-full rounded-2xl border border-line bg-surface select-none ${pan ? "cursor-grabbing" : "cursor-default"}`}
        // Always fits the screen's height; the drawing keeps its shape inside.
        style={{ aspectRatio: `${view.width} / ${view.height}`, maxHeight: fill ? "calc(100dvh - 6rem)" : "calc(100dvh - 15rem)", minHeight: 280, touchAction: "pan-y" }}
        onPointerDownCapture={() => setActive(true)}
        onPointerDown={onBackgroundDown}
        onPointerMove={onBackgroundMove}
        onPointerUp={onBackgroundUp}
        onPointerCancel={onBackgroundUp}
      >
        <defs>
          <pattern id="floor-grid" width={room.gridStepMeters * 4} height={room.gridStepMeters * 4} patternUnits="userSpaceOnUse">
            <circle cx={room.gridStepMeters * 2} cy={room.gridStepMeters * 2} r="0.035" fill="var(--plan-grid)" />
          </pattern>
        </defs>
        {/* The empty floor: grabbing it pans the plan. */}
        <rect id="floor-background" x={view.x} y={view.y} width={view.width} height={view.height} fill="transparent" />
        {/* The room itself, so it is clear where it ends. */}
        <rect data-testid="room-outline" x="0" y="0" width={W} height={H} fill="url(#floor-grid)" stroke="var(--plan-room-line)" strokeWidth="0.08" rx="0.1" style={{ pointerEvents: "none" }} />

        {layout.obstacles.map((o) => {
          const { x, y, width, height, rotation } = geometry("obstacle", o);
          const selected = isSelected("obstacle", o.id);
          const round = o.type === "COLUMN";
          const shape = { fill: OBSTACLE_FILL, stroke: selected ? SELECTED : OBSTACLE_STROKE, strokeWidth: selected ? 0.1 : 0.04 };
          return (
            <g
              key={o.id}
              role="button"
              tabIndex={0}
              aria-label={o.name || te(o.type)}
              aria-pressed={selected}
              className="group"
              style={elementStyle}
              onPointerDown={(e) => onElementDown(e, "obstacle", o)}
              onPointerMove={onElementMove}
              onPointerUp={finish}
              onPointerCancel={finish}
              onKeyDown={(e) => onElementKey(e, "obstacle", o)}
              transform={`translate(${x} ${y})`}
            >
              <g transform={`rotate(${rotation})`}>
                {round ? (
                  <ellipse rx={width / 2} ry={height / 2} {...shape} />
                ) : (
                  <rect x={-width / 2} y={-height / 2} width={width} height={height} rx="0.05" {...shape} />
                )}
                {/* Keyboard focus ring, drawn as part of the picture (browsers draw a poor one around SVG groups). */}
                <rect
                  x={-width / 2 - 0.12}
                  y={-height / 2 - 0.12}
                  width={width + 0.24}
                  height={height + 0.24}
                  rx="0.15"
                  fill="none"
                  stroke={SELECTED}
                  strokeWidth="0.06"
                  strokeDasharray="0.15 0.1"
                  className="opacity-0 group-focus-visible:opacity-100"
                />
              </g>
              {width >= 1 && height >= 0.45 ? (
                <text textAnchor="middle" dominantBaseline="central" fontSize={label * 0.8} fontWeight="700" fill="var(--plan-text)" style={{ pointerEvents: "none" }}>
                  {(o.name || te(o.type)).toUpperCase()}
                </text>
              ) : null}
            </g>
          );
        })}

        {layout.tables.map(({ table, liveStatus }) => {
          const { x, y, width, height, rotation } = geometry("table", table);
          const selected = isSelected("table", table.id);
          const style = LIVE_STYLE[liveStatus];
          const shape = { fill: style.fill, stroke: selected ? SELECTED : style.stroke, strokeWidth: selected ? 0.1 : 0.05, strokeDasharray: style.dash };
          const ringW = width + 0.3;
          const ringH = height + 0.3;
          return (
            <g
              key={table.id}
              role="button"
              tabIndex={0}
              aria-label={`${t("table")} ${table.number}, ${t("seatsShort", { count: table.seats })}, ${t(`live${liveStatus}`)}`}
              aria-pressed={selected}
              className="group"
              style={elementStyle}
              onPointerDown={(e) => onElementDown(e, "table", table)}
              onPointerMove={onElementMove}
              onPointerUp={finish}
              onPointerCancel={finish}
              onKeyDown={(e) => onElementKey(e, "table", table)}
              transform={`translate(${x} ${y})`}
            >
              <g transform={`rotate(${rotation})`}>
                {selected ? (
                  table.shape === "ROUND" ? (
                    <ellipse rx={ringW / 2} ry={ringH / 2} fill="none" stroke={SELECTED} strokeWidth="0.05" opacity="0.35" />
                  ) : (
                    <rect x={-ringW / 2} y={-ringH / 2} width={ringW} height={ringH} rx="0.18" fill="none" stroke={SELECTED} strokeWidth="0.05" opacity="0.35" />
                  )
                ) : null}
                {table.shape === "ROUND" ? (
                  <ellipse rx={width / 2} ry={height / 2} {...shape} />
                ) : (
                  <rect x={-width / 2} y={-height / 2} width={width} height={height} rx="0.1" {...shape} />
                )}
                {table.shape === "ROUND" ? (
                  <ellipse rx={ringW / 2} ry={ringH / 2} fill="none" stroke={SELECTED} strokeWidth="0.06" strokeDasharray="0.15 0.1" className="opacity-0 group-focus-visible:opacity-100" />
                ) : (
                  <rect x={-ringW / 2} y={-ringH / 2} width={ringW} height={ringH} rx="0.18" fill="none" stroke={SELECTED} strokeWidth="0.06" strokeDasharray="0.15 0.1" className="opacity-0 group-focus-visible:opacity-100" />
                )}
              </g>
              {(() => {
                // With a booking time to show, the three lines sit a little higher so they all stay inside the table.
                const booked = bookings?.[table.id];
                const shift = booked ? -0.1 : 0;
                return (
                  <>
                    <text textAnchor="middle" y={-0.02 + shift} fontSize={label} fontWeight="800" fill={style.text} style={{ pointerEvents: "none" }}>
                      {table.number}
                    </text>
                    <text textAnchor="middle" y={label * 0.9 + shift} fontSize={label * 0.62} fontWeight="600" fill={style.text} style={{ pointerEvents: "none" }}>
                      {t("seatsShort", { count: table.seats })}
                    </text>
                    {booked ? (
                      <text textAnchor="middle" y={label * 0.9 + shift + label * 0.62} fontSize={label * 0.6} fontWeight="800" fill={style.text} style={{ pointerEvents: "none" }}>
                        {booked}
                      </text>
                    ) : null}
                  </>
                );
              })()}
            </g>
          );
        })}

        {/* Handles go on top of everything so they are never hidden behind a neighbour. */}
        {editable && selectedTable && gesture?.type !== "room" ? renderHandles("table", selectedTable) : null}
        {editable && selectedObstacle && gesture?.type !== "room" ? renderHandles("obstacle", selectedObstacle) : null}

        {/* Grab points on the room's right and bottom edges and its corner. */}
        {editable
          ? (
              [
                { edge: "e" as const, x: W, y: H / 2, cursor: "ew-resize" },
                { edge: "s" as const, x: W / 2, y: H, cursor: "ns-resize" },
                { edge: "se" as const, x: W, y: H, cursor: "nwse-resize" },
              ]
            ).map((h) => (
              <g
                key={h.edge}
                data-room-handle={h.edge}
                transform={`translate(${h.x} ${h.y})`}
                style={{ cursor: h.cursor, touchAction: "none" }}
                onPointerDown={(e) => startRoom(e, h.edge)}
                onPointerMove={moveRoom}
                onPointerUp={finish}
                onPointerCancel={finish}
              >
                <circle r={px(24)} fill="transparent" />
                <rect x={-px(8)} y={-px(8)} width={px(16)} height={px(16)} rx={px(4)} fill="var(--color-primary)" stroke="var(--color-surface)" strokeWidth={px(2)} />
              </g>
            ))
          : null}

        {renderReadout()}

        {/* Scale bar, bottom left of what is on screen. */}
        <g transform={`translate(${view.x + view.width * 0.03} ${view.y + view.height * 0.95})`} style={{ pointerEvents: "none" }}>
          <rect x="0" y="-0.05" width={bar} height="0.1" fill="var(--plan-text)" rx="0.03" />
          <text x={bar / 2} y="-0.14" textAnchor="middle" fontSize={view.width * 0.015} fontWeight="700" fill="var(--plan-text)">
            {t("scale", { metres: bar })}
          </text>
        </g>
      </svg>

    </div>
  );
}

export const LIVE_STATUSES: Live[] = ["FREE", "RESERVED", "OCCUPIED", "TO_CLEAN", "OUT_OF_SERVICE"];
export const liveSwatch = (live: Live) => LIVE_STYLE[live];
