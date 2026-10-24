"use client";

import { useTranslations } from "next-intl";
import { useRef, useState, type KeyboardEvent, type PointerEvent } from "react";
import type { components } from "@/api/schema";
import { dragTarget, moveByKey } from "./geometry";

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
const OBSTACLE_STROKE = "#b8c4bf";

interface Drag {
  kind: "table" | "obstacle";
  id: string;
  grabX: number;
  grabY: number;
  x: number;
  y: number;
  moved: boolean;
}

/**
 * The room drawn to scale. Coordinates are in metres and the SVG scales to fit the
 * screen. Editors can drag elements (snapped to the grid) or nudge the selected one
 * with the arrow keys; everyone can select an element to see its details.
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
  const tl = useTranslations("floor");
  const te = useTranslations("elements");
  const svgRef = useRef<SVGSVGElement>(null);
  const [drag, setDrag] = useState<Drag | null>(null);
  const { room } = layout;

  function pointerToMeters(event: PointerEvent): { x: number; y: number } | null {
    const svg = svgRef.current;
    const matrix = svg?.getScreenCTM();
    if (!svg || !matrix) return null;
    const point = svg.createSVGPoint();
    point.x = event.clientX;
    point.y = event.clientY;
    const local = point.matrixTransform(matrix.inverse());
    return { x: local.x, y: local.y };
  }

  function onPointerDown(event: PointerEvent<SVGGElement>, kind: "table" | "obstacle", el: Table | Obstacle) {
    onSelect({ kind, id: el.id });
    if (!editable) return;
    const p = pointerToMeters(event);
    if (!p) return;
    event.currentTarget.setPointerCapture(event.pointerId);
    setDrag({ kind, id: el.id, grabX: p.x - el.xMeters, grabY: p.y - el.yMeters, x: el.xMeters, y: el.yMeters, moved: false });
  }

  function onPointerMove(event: PointerEvent<SVGGElement>) {
    if (!drag) return;
    const p = pointerToMeters(event);
    if (!p) return;
    const next = dragTarget(p, { x: drag.grabX, y: drag.grabY }, room.gridStepMeters, room.widthMeters, room.heightMeters);
    setDrag({ ...drag, ...next, moved: drag.moved || next.x !== drag.x || next.y !== drag.y });
  }

  function onPointerUp(event: PointerEvent<SVGGElement>) {
    if (!drag) return;
    event.currentTarget.releasePointerCapture(event.pointerId);
    if (drag.moved) onMove(drag.kind, drag.id, drag.x, drag.y);
    setDrag(null);
  }

  function onKeyDown(event: KeyboardEvent<SVGGElement>, kind: "table" | "obstacle", el: Table | Obstacle) {
    if (event.key === "Enter" || event.key === " ") {
      event.preventDefault();
      onSelect({ kind, id: el.id });
      return;
    }
    if (!editable) return;
    const next = moveByKey(event.key, el.xMeters, el.yMeters, room.gridStepMeters, room.widthMeters, room.heightMeters);
    if (next) {
      event.preventDefault();
      onSelect({ kind, id: el.id });
      onMove(kind, el.id, next.x, next.y);
    }
  }

  const position = (kind: "table" | "obstacle", el: Table | Obstacle) =>
    drag && drag.kind === kind && drag.id === el.id ? { x: drag.x, y: drag.y } : { x: el.xMeters, y: el.yMeters };
  const isSelected = (kind: "table" | "obstacle", id: string) => selection?.kind === kind && selection.id === id;
  const interactive = { cursor: editable ? "grab" : "pointer", touchAction: editable ? ("none" as const) : ("auto" as const) };
  const labelSize = 0.3;

  return (
    <svg
      ref={svgRef}
      viewBox={`0 0 ${room.widthMeters} ${room.heightMeters}`}
      role="group"
      aria-label={room.name}
      className="h-auto w-full rounded-2xl border border-line bg-surface select-none"
      onPointerDown={(e) => {
        if (e.target === e.currentTarget || (e.target as Element).id === "floor-background") onSelect(null);
      }}
    >
      <defs>
        <pattern id="floor-grid" width={room.gridStepMeters * 4} height={room.gridStepMeters * 4} patternUnits="userSpaceOnUse">
          <circle cx="0" cy="0" r="0.03" fill="#c9d3ce" />
        </pattern>
      </defs>
      <rect id="floor-background" x="0" y="0" width={room.widthMeters} height={room.heightMeters} fill="url(#floor-grid)" />

      {layout.obstacles.map((o) => {
        const { x, y } = position("obstacle", o);
        const selected = isSelected("obstacle", o.id);
        const round = o.type === "COLUMN";
        return (
          <g
            key={o.id}
            role="button"
            tabIndex={0}
            aria-label={o.name || te(o.type)}
            aria-pressed={selected}
            style={interactive}
            onPointerDown={(e) => onPointerDown(e, "obstacle", o)}
            onPointerMove={onPointerMove}
            onPointerUp={onPointerUp}
            onKeyDown={(e) => onKeyDown(e, "obstacle", o)}
            transform={`translate(${x} ${y})`}
          >
            <g transform={`rotate(${o.rotationDegrees})`}>
              {round ? (
                <ellipse rx={o.widthMeters / 2} ry={o.heightMeters / 2} fill={OBSTACLE_FILL} stroke={selected ? "#1d4ed8" : OBSTACLE_STROKE} strokeWidth={selected ? 0.07 : 0.03} />
              ) : (
                <rect x={-o.widthMeters / 2} y={-o.heightMeters / 2} width={o.widthMeters} height={o.heightMeters} rx="0.05" fill={OBSTACLE_FILL} stroke={selected ? "#1d4ed8" : OBSTACLE_STROKE} strokeWidth={selected ? 0.07 : 0.03} />
              )}
            </g>
            {o.widthMeters >= 1 && o.heightMeters >= 0.45 ? (
              <text textAnchor="middle" dominantBaseline="central" fontSize={labelSize * 0.8} fontWeight="700" fill="#3f4f48" style={{ pointerEvents: "none" }}>
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
        const stroke = selected ? "#1d4ed8" : style.stroke;
        const common = { fill: style.fill, stroke, strokeWidth: selected ? 0.09 : 0.05, strokeDasharray: style.dash };
        return (
          <g
            key={table.id}
            role="button"
            tabIndex={0}
            aria-label={`${t("table")} ${table.number}, ${tl("seatsShort", { count: table.seats })}, ${tl(`live${liveStatus}`)}`}
            aria-pressed={selected}
            style={interactive}
            onPointerDown={(e) => onPointerDown(e, "table", table)}
            onPointerMove={onPointerMove}
            onPointerUp={onPointerUp}
            onKeyDown={(e) => onKeyDown(e, "table", table)}
            transform={`translate(${x} ${y})`}
          >
            <g transform={`rotate(${table.rotationDegrees})`}>
              {table.shape === "ROUND" ? (
                <ellipse rx={table.widthMeters / 2} ry={table.heightMeters / 2} {...common} />
              ) : (
                <rect x={-table.widthMeters / 2} y={-table.heightMeters / 2} width={table.widthMeters} height={table.heightMeters} rx="0.1" {...common} />
              )}
            </g>
            <text textAnchor="middle" y={-0.02} dominantBaseline="auto" fontSize={labelSize} fontWeight="800" fill={style.text} style={{ pointerEvents: "none" }}>
              {table.number}
            </text>
            <text textAnchor="middle" y={labelSize * 0.9} fontSize={labelSize * 0.62} fontWeight="600" fill={style.text} style={{ pointerEvents: "none" }}>
              {tl("seatsShort", { count: table.seats })}
            </text>
          </g>
        );
      })}
    </svg>
  );
}

export const LIVE_STATUSES: Live[] = ["FREE", "RESERVED", "OCCUPIED", "TO_CLEAN", "OUT_OF_SERVICE"];
export const liveSwatch = (live: Live) => LIVE_STYLE[live];
