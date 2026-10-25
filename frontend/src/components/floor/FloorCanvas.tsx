"use client";

import { useTranslations } from "next-intl";
import { useRef, useState, type KeyboardEvent, type PointerEvent as ReactPointerEvent } from "react";
import type { components } from "@/api/schema";
import { dragTarget, fitView, moveByKey, panBy, scaleBarMetres, zoomAt, zoomLevel, type View } from "./geometry";

type Layout = components["schemas"]["Layout"];
type Table = components["schemas"]["DiningTable"];
type Obstacle = components["schemas"]["Obstacle"];
type Live = components["schemas"]["TableView"]["liveStatus"];

export type Selection = { kind: "table" | "obstacle"; id: string } | null;

/** Colours of a table by what is happening at it right now. Text and outline differ in lightness, not just hue. */
const LIVE_STYLE: Record<Live, { fill: string; stroke: string; text: string; dash?: string }> = {
  FREE: { fill: "var(--color-primary-soft)", stroke: "var(--color-primary)", text: "var(--color-ink)" },
  RESERVED: { fill: "var(--color-warn-soft)", stroke: "#b45309", text: "var(--color-warn-ink)" },
  OCCUPIED: { fill: "var(--color-primary)", stroke: "var(--color-primary-strong)", text: "#ffffff" },
  TO_CLEAN: { fill: "var(--color-info-soft)", stroke: "#1d4ed8", text: "var(--color-info-ink)" },
  OUT_OF_SERVICE: { fill: "#e3e8e5", stroke: "#6b7a74", text: "#3f4f48", dash: "0.12 0.08" },
};

const OBSTACLE_FILL = "#e3e8e5";
const OBSTACLE_STROKE = "#9aa9a3";
const SELECTED = "#1d4ed8";

interface Drag {
  kind: "table" | "obstacle";
  id: string;
  grabX: number;
  grabY: number;
  x: number;
  y: number;
  moved: boolean;
}

interface Pan {
  pointerId: number;
  lastX: number;
  lastY: number;
}

/**
 * The room drawn to scale. Coordinates are in metres. The picture always fits the screen
 * height; zoom in with the buttons or Ctrl + scroll, and drag the empty floor to move around.
 * Editors can also drag elements (snapped to the grid) or nudge the selected one with the arrow keys.
 */
export function FloorCanvas({
  layout,
  selection,
  editable,
  onSelect,
  onMove,
}: {
  layout: Layout;
  selection: Selection;
  editable: boolean;
  onSelect: (selection: Selection) => void;
  onMove: (kind: "table" | "obstacle", id: string, x: number, y: number) => void;
}) {
  const t = useTranslations("floor");
  const te = useTranslations("elements");
  const svgRef = useRef<SVGSVGElement>(null);
  const { room } = layout;
  const W = room.widthMeters;
  const H = room.heightMeters;

  const [view, setView] = useState<View>(() => fitView(W, H));
  const [drag, setDrag] = useState<Drag | null>(null);
  const [pan, setPan] = useState<Pan | null>(null);

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

  // --- dragging an element
  function onElementDown(event: ReactPointerEvent<SVGGElement>, kind: "table" | "obstacle", el: Table | Obstacle) {
    event.stopPropagation();
    onSelect({ kind, id: el.id });
    if (!editable) return;
    const p = toMeters(event.clientX, event.clientY);
    if (!p) return;
    event.currentTarget.setPointerCapture(event.pointerId);
    setDrag({ kind, id: el.id, grabX: p.x - el.xMeters, grabY: p.y - el.yMeters, x: el.xMeters, y: el.yMeters, moved: false });
  }

  function onElementMove(event: ReactPointerEvent<SVGGElement>) {
    if (!drag) return;
    const p = toMeters(event.clientX, event.clientY);
    if (!p) return;
    const next = dragTarget(p, { x: drag.grabX, y: drag.grabY }, room.gridStepMeters, W, H);
    setDrag({ ...drag, ...next, moved: drag.moved || next.x !== drag.x || next.y !== drag.y });
  }

  function onElementUp(event: ReactPointerEvent<SVGGElement>) {
    if (!drag) return;
    event.currentTarget.releasePointerCapture(event.pointerId);
    if (drag.moved) onMove(drag.kind, drag.id, drag.x, drag.y);
    setDrag(null);
  }

  function onElementKey(event: KeyboardEvent<SVGGElement>, kind: "table" | "obstacle", el: Table | Obstacle) {
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
      onMove(kind, el.id, next.x, next.y);
    }
  }

  const position = (kind: "table" | "obstacle", el: Table | Obstacle) =>
    drag && drag.kind === kind && drag.id === el.id ? { x: drag.x, y: drag.y } : { x: el.xMeters, y: el.yMeters };
  const isSelected = (kind: "table" | "obstacle", id: string) => selection?.kind === kind && selection.id === id;
  const elementStyle = { cursor: editable ? "grab" : "pointer", touchAction: editable ? ("none" as const) : ("auto" as const), outline: "none" };
  const label = 0.3;
  const bar = scaleBarMetres(view.width);
  const level = zoomLevel(view, W, H);

  return (
    <div className="relative">
      <svg
        ref={svgRef}
        viewBox={`${view.x} ${view.y} ${view.width} ${view.height}`}
        role="group"
        aria-label={room.name}
        className={`block w-full rounded-2xl border border-line bg-surface select-none ${pan ? "cursor-grabbing" : "cursor-default"}`}
        // Always fits the screen's height; the drawing keeps its shape inside.
        style={{ aspectRatio: `${view.width} / ${view.height}`, maxHeight: "calc(100dvh - 14rem)", minHeight: 280, touchAction: "pan-y" }}
        onPointerDown={onBackgroundDown}
        onPointerMove={onBackgroundMove}
        onPointerUp={onBackgroundUp}
        onPointerCancel={onBackgroundUp}
        onWheel={(event) => {
          // Plain scrolling still scrolls the page. Ctrl (or pinch on a trackpad) zooms the plan.
          if (!event.ctrlKey && !event.metaKey) return;
          event.preventDefault();
          zoomBy(event.deltaY < 0 ? 1.15 : 1 / 1.15, toMeters(event.clientX, event.clientY) ?? undefined);
        }}
      >
        <defs>
          <pattern id="floor-grid" width={room.gridStepMeters * 4} height={room.gridStepMeters * 4} patternUnits="userSpaceOnUse">
            <circle cx={room.gridStepMeters * 2} cy={room.gridStepMeters * 2} r="0.035" fill="#b8c4bf" />
          </pattern>
        </defs>
        {/* The empty floor: grabbing it pans the plan. */}
        <rect id="floor-background" x={view.x} y={view.y} width={view.width} height={view.height} fill="transparent" />
        {/* The room itself, so it is clear where it ends. */}
        <rect x="0" y="0" width={W} height={H} fill="url(#floor-grid)" stroke="#6b7a74" strokeWidth="0.08" rx="0.1" style={{ pointerEvents: "none" }} />

        {layout.obstacles.map((o) => {
          const { x, y } = position("obstacle", o);
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
              onPointerUp={onElementUp}
              onKeyDown={(e) => onElementKey(e, "obstacle", o)}
              transform={`translate(${x} ${y})`}
            >
              <g transform={`rotate(${o.rotationDegrees})`}>
                {round ? (
                  <ellipse rx={o.widthMeters / 2} ry={o.heightMeters / 2} {...shape} />
                ) : (
                  <rect x={-o.widthMeters / 2} y={-o.heightMeters / 2} width={o.widthMeters} height={o.heightMeters} rx="0.05" {...shape} />
                )}
                {/* Keyboard focus ring, drawn as part of the picture (browsers draw a poor one around SVG groups). */}
                <rect
                  x={-o.widthMeters / 2 - 0.12}
                  y={-o.heightMeters / 2 - 0.12}
                  width={o.widthMeters + 0.24}
                  height={o.heightMeters + 0.24}
                  rx="0.15"
                  fill="none"
                  stroke={SELECTED}
                  strokeWidth="0.06"
                  strokeDasharray="0.15 0.1"
                  className="opacity-0 group-focus-visible:opacity-100"
                />
              </g>
              {o.widthMeters >= 1 && o.heightMeters >= 0.45 ? (
                <text textAnchor="middle" dominantBaseline="central" fontSize={label * 0.8} fontWeight="700" fill="#3f4f48" style={{ pointerEvents: "none" }}>
                  {(o.name || te(o.type)).toUpperCase()}
                </text>
              ) : null}
            </g>
          );
        })}

        {layout.tables.map(({ table, liveStatus }) => {
          const { x, y } = position("table", table);
          const selected = isSelected("table", table.id);
          const style = LIVE_STYLE[liveStatus];
          const shape = { fill: style.fill, stroke: selected ? SELECTED : style.stroke, strokeWidth: selected ? 0.1 : 0.05, strokeDasharray: style.dash };
          const ringW = table.widthMeters + 0.3;
          const ringH = table.heightMeters + 0.3;
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
              onPointerUp={onElementUp}
              onKeyDown={(e) => onElementKey(e, "table", table)}
              transform={`translate(${x} ${y})`}
            >
              <g transform={`rotate(${table.rotationDegrees})`}>
                {selected ? (
                  table.shape === "ROUND" ? (
                    <ellipse rx={ringW / 2} ry={ringH / 2} fill="none" stroke={SELECTED} strokeWidth="0.05" opacity="0.35" />
                  ) : (
                    <rect x={-ringW / 2} y={-ringH / 2} width={ringW} height={ringH} rx="0.18" fill="none" stroke={SELECTED} strokeWidth="0.05" opacity="0.35" />
                  )
                ) : null}
                {table.shape === "ROUND" ? (
                  <ellipse rx={table.widthMeters / 2} ry={table.heightMeters / 2} {...shape} />
                ) : (
                  <rect x={-table.widthMeters / 2} y={-table.heightMeters / 2} width={table.widthMeters} height={table.heightMeters} rx="0.1" {...shape} />
                )}
                {table.shape === "ROUND" ? (
                  <ellipse rx={ringW / 2} ry={ringH / 2} fill="none" stroke={SELECTED} strokeWidth="0.06" strokeDasharray="0.15 0.1" className="opacity-0 group-focus-visible:opacity-100" />
                ) : (
                  <rect x={-ringW / 2} y={-ringH / 2} width={ringW} height={ringH} rx="0.18" fill="none" stroke={SELECTED} strokeWidth="0.06" strokeDasharray="0.15 0.1" className="opacity-0 group-focus-visible:opacity-100" />
                )}
              </g>
              <text textAnchor="middle" y={-0.02} fontSize={label} fontWeight="800" fill={style.text} style={{ pointerEvents: "none" }}>
                {table.number}
              </text>
              <text textAnchor="middle" y={label * 0.9} fontSize={label * 0.62} fontWeight="600" fill={style.text} style={{ pointerEvents: "none" }}>
                {t("seatsShort", { count: table.seats })}
              </text>
            </g>
          );
        })}

        {/* Scale bar, bottom left of what is on screen. */}
        <g transform={`translate(${view.x + view.width * 0.03} ${view.y + view.height * 0.95})`} style={{ pointerEvents: "none" }}>
          <rect x="0" y="-0.05" width={bar} height="0.1" fill="#3f4f48" rx="0.03" />
          <text x={bar / 2} y="-0.14" textAnchor="middle" fontSize={view.width * 0.015} fontWeight="700" fill="#3f4f48">
            {t("scale", { metres: bar })}
          </text>
        </g>
      </svg>

      <div className="absolute top-3 right-3 flex flex-col overflow-hidden rounded-xl border border-line-strong bg-surface shadow-sm" role="group" aria-label={t("fit")}>
        <button type="button" aria-label={t("zoomIn")} onClick={() => zoomBy(1.4)} className="flex min-h-11 min-w-11 items-center justify-center text-xl font-bold hover:bg-canvas">
          +
        </button>
        <button type="button" aria-label={t("zoomOut")} onClick={() => zoomBy(1 / 1.4)} className="flex min-h-11 min-w-11 items-center justify-center border-t border-line text-xl font-bold hover:bg-canvas">
          −
        </button>
        <button
          type="button"
          aria-label={t("fit")}
          title={t("fit")}
          disabled={level === 1 && view.x === fitView(W, H).x}
          onClick={() => setView(fitView(W, H))}
          className="flex min-h-11 min-w-11 items-center justify-center border-t border-line text-xs font-extrabold hover:bg-canvas disabled:opacity-40"
        >
          {Math.round(level * 100)}%
        </button>
      </div>
    </div>
  );
}

export const LIVE_STATUSES: Live[] = ["FREE", "RESERVED", "OCCUPIED", "TO_CLEAN", "OUT_OF_SERVICE"];
export const liveSwatch = (live: Live) => LIVE_STYLE[live];
