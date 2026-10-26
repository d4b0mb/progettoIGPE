"use client";

import { useTranslations } from "next-intl";
import { useCallback, useEffect, useRef, useState } from "react";
import { api } from "@/api/client";
import { expectOk, unwrap } from "@/api/call";
import type { components } from "@/api/schema";
import { useSession } from "@/api/session";
import { Button } from "@/components/ui/Button";
import { Dialog } from "@/components/ui/Dialog";
import { useRemote } from "@/lib/useRemote";
import { FloorCanvas, LIVE_STATUSES, liveSwatch, type Selection } from "./FloorCanvas";
import { clamp, defaultObstacleSize, snap, tidy, type Geom, type ObstacleType } from "./geometry";
import { EmptyInspector, OBSTACLE_TYPES, ObstacleInspector, TableInspector } from "./Inspector";
import { RoomForm } from "./RoomForm";

type Room = components["schemas"]["Room"];
type Layout = components["schemas"]["Layout"];

export function FloorScreen() {
  const t = useTranslations("floor");
  const { state } = useSession();
  const [chosen, setChosen] = useState<string | null>(null);
  const [roomDialog, setRoomDialog] = useState<"new" | "edit" | null>(null);

  const loadRooms = useCallback(() => unwrap(api.GET("/api/v1/rooms")), []);
  const rooms = useRemote(loadRooms);

  if (state.status !== "authenticated") return null;
  const canEdit = state.user.role === "OWNER" || state.user.role === "MANAGER";
  const list = rooms.data ?? [];
  const room: Room | undefined = list.find((r) => r.id === chosen) ?? list[0];

  return (
    <div className="flex flex-col gap-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-2xl font-extrabold tracking-tight">{t("title")}</h1>
        {canEdit && room ? (
          <Button variant="secondary" onClick={() => setRoomDialog("edit")}>
            {t("editRoom")}
          </Button>
        ) : null}
      </div>

      {rooms.data && list.length === 0 ? (
        <div className="flex flex-col items-start gap-4 rounded-2xl border border-dashed border-line-strong p-8">
          <p className="text-muted">{canEdit ? t("emptyOwner") : t("emptyOther")}</p>
          {canEdit ? <Button onClick={() => setRoomDialog("new")}>{t("newRoom")}</Button> : null}
        </div>
      ) : null}

      {list.length > 0 ? (
        <div className="flex flex-wrap gap-2">
          {list.map((r) => (
            <button
              key={r.id}
              type="button"
              aria-pressed={r.id === room?.id}
              onClick={() => setChosen(r.id)}
              className={`min-h-11 rounded-full border px-4 text-sm font-bold ${r.id === room?.id ? "border-primary bg-primary-soft text-primary-strong" : "border-line bg-surface text-muted"}`}
            >
              {r.name}
            </button>
          ))}
          {canEdit ? (
            <button type="button" onClick={() => setRoomDialog("new")} className="min-h-11 rounded-full border border-dashed border-line-strong px-4 text-sm font-bold text-muted">
              + {t("newRoom")}
            </button>
          ) : null}
        </div>
      ) : null}

      {room ? (
        <RoomView key={room.id} room={room} canEdit={canEdit} onRoomChanged={rooms.reload} />
      ) : null}

      <Dialog open={roomDialog === "new"} onClose={() => setRoomDialog(null)} title={t("newRoom")}>
        <RoomForm
          onCancel={() => setRoomDialog(null)}
          onSaved={(created) => {
            setRoomDialog(null);
            setChosen(created.id);
            rooms.reload();
          }}
        />
      </Dialog>
      <Dialog open={roomDialog === "edit"} onClose={() => setRoomDialog(null)} title={t("editRoom")}>
        {room ? (
          <div className="flex flex-col gap-5">
            <RoomForm
              existing={room}
              onCancel={() => setRoomDialog(null)}
              onSaved={() => {
                setRoomDialog(null);
                rooms.reload();
              }}
            />
            <DeleteRoom room={room} onDeleted={() => { setRoomDialog(null); setChosen(null); rooms.reload(); }} />
          </div>
        ) : null}
      </Dialog>
    </div>
  );
}

function DeleteRoom({ room, onDeleted }: { room: Room; onDeleted: () => void }) {
  const t = useTranslations("floor");
  const [error, setError] = useState<string | null>(null);
  async function remove() {
    if (!window.confirm(t("deleteRoomConfirm", { name: room.name }))) return;
    try {
      await expectOk(api.DELETE("/api/v1/rooms/{id}", { params: { path: { id: room.id } } }));
      onDeleted();
    } catch (e) {
      setError((e as Error).message);
    }
  }
  return (
    <div className="flex flex-col gap-2 border-t border-line pt-4">
      {error ? (
        <p role="alert" className="rounded-lg bg-danger-soft px-3 py-2 text-sm font-semibold text-danger-ink">
          {error}
        </p>
      ) : null}
      <div>
        <Button variant="danger" onClick={() => void remove()}>
          {t("deleteRoom")}
        </Button>
      </div>
    </div>
  );
}

type TableShape = components["schemas"]["DiningTable"]["shape"];
type Table = components["schemas"]["DiningTable"];
type Obstacle = components["schemas"]["Obstacle"];
type Kind = "table" | "obstacle";

const geomOf = (el: Table | Obstacle): Geom => ({ x: el.xMeters, y: el.yMeters, width: el.widthMeters, height: el.heightMeters, rotation: el.rotationDegrees });

/** What each kind of table starts as, in metres. */
const TABLE_KINDS: { shape: TableShape; width: number; height: number; label: "tableRound" | "tableSquare" | "tableRectangle" }[] = [
  { shape: "ROUND", width: 1, height: 1, label: "tableRound" },
  { shape: "SQUARE", width: 0.9, height: 0.9, label: "tableSquare" },
  { shape: "RECTANGLE", width: 1.4, height: 0.9, label: "tableRectangle" },
];

/** One room: the drawing, the palette to add things to it, and the details of whatever is selected. */
function RoomView({ room, canEdit, onRoomChanged }: { room: Room; canEdit: boolean; onRoomChanged: () => void }) {
  const t = useTranslations("floor");
  const te = useTranslations("elements");
  const [layout, setLayout] = useState<Layout | null>(null);
  const [selection, setSelection] = useState<Selection>(null);
  const [error, setError] = useState<string | null>(null);
  const [snapOn, setSnapOn] = useState(true);
  // On a phone the palette folds away so the plan stays in view; on a bigger screen it is always open.
  const [paletteOpen, setPaletteOpen] = useState(false);
  // Expanded: the plan and its tools take the whole window; Escape goes back.
  const [expanded, setExpanded] = useState(false);
  const [undo, setUndo] = useState<{ kind: Kind; id: string; before: Geom } | null>(null);
  const viewCenter = useRef({ x: room.widthMeters / 2, y: room.heightMeters / 2 });

  useEffect(() => {
    if (!expanded) return;
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") setExpanded(false);
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [expanded]);

  const fetchLayout = useCallback(() => unwrap(api.GET("/api/v1/rooms/{id}/layout", { params: { path: { id: room.id } } })), [room.id]);

  // Load again whenever the room itself was edited (name, size), so a change never needs a page reload.
  useEffect(() => {
    let cancelled = false;
    fetchLayout().then(
      (l) => !cancelled && setLayout(l),
      (e: Error) => !cancelled && setError(e.message),
    );
    return () => {
      cancelled = true;
    };
  }, [fetchLayout, room.name, room.widthMeters, room.heightMeters, room.gridStepMeters]);

  const refresh = useCallback(async () => {
    try {
      setLayout(await fetchLayout());
    } catch (e) {
      setError((e as Error).message);
    }
  }, [fetchLayout]);

  if (!layout) return <p className="text-muted">…</p>;
  const grid = layout.room.gridStepMeters;

  /** Where a new object appears: the middle of what is on screen, on the grid, inside the room. */
  function spot() {
    const c = viewCenter.current;
    return {
      x: tidy(clamp(snap(c.x, grid), 0, layout!.room.widthMeters)),
      y: tidy(clamp(snap(c.y, grid), 0, layout!.room.heightMeters)),
    };
  }

  async function addTable(shape: TableShape, width: number, height: number) {
    setError(null);
    try {
      const created = await unwrap(
        api.POST("/api/v1/rooms/{roomId}/tables", { params: { path: { roomId: room.id } }, body: { xMeters: spot().x, yMeters: spot().y, widthMeters: width, heightMeters: height, shape } }),
      );
      await refresh();
      setSelection({ kind: "table", id: created.id });
    } catch (e) {
      setError((e as Error).message);
    }
  }

  async function addElement(type: ObstacleType) {
    setError(null);
    const size = defaultObstacleSize(type);
    try {
      const created = await unwrap(
        api.POST("/api/v1/rooms/{roomId}/obstacles", { params: { path: { roomId: room.id } }, body: { type, xMeters: spot().x, yMeters: spot().y, widthMeters: size.width, heightMeters: size.height } }),
      );
      await refresh();
      setSelection({ kind: "obstacle", id: created.id });
    } catch (e) {
      setError((e as Error).message);
    }
  }

  /**
   * Saves a new position, size or turn. The drawing shows it at once, the server gets the change, and then
   * the picture is reloaded from the server so it always shows what was really saved. Resizing a table lets
   * the server work out its seats again; moving or turning one keeps them.
   */
  async function saveGeometry(kind: Kind, id: string, geom: Geom, remember = true) {
    setError(null);
    const current = kind === "table" ? layout!.tables.find((v) => v.table.id === id)?.table : layout!.obstacles.find((o) => o.id === id);
    if (!current) return;
    const before = geomOf(current);
    if (remember) setUndo({ kind, id, before });
    setLayout((l) =>
      l && {
        ...l,
        tables: l.tables.map((v) => (kind === "table" && v.table.id === id ? { ...v, table: { ...v.table, xMeters: geom.x, yMeters: geom.y, widthMeters: geom.width, heightMeters: geom.height, rotationDegrees: geom.rotation } } : v)),
        obstacles: l.obstacles.map((o) => (kind === "obstacle" && o.id === id ? { ...o, xMeters: geom.x, yMeters: geom.y, widthMeters: geom.width, heightMeters: geom.height, rotationDegrees: geom.rotation } : o)),
      },
    );
    try {
      if (kind === "table") {
        const tb = current as Table;
        const sizeChanged = geom.width !== before.width || geom.height !== before.height;
        await unwrap(
          api.PUT("/api/v1/tables/{id}", {
            params: { path: { id } },
            body: {
              number: tb.number,
              name: tb.name,
              seats: sizeChanged ? undefined : tb.seats,
              shape: tb.shape,
              widthMeters: geom.width,
              heightMeters: geom.height,
              rotationDegrees: geom.rotation,
              accessible: tb.accessible,
              xMeters: geom.x,
              yMeters: geom.y,
            },
          }),
        );
      } else {
        const ob = current as Obstacle;
        await unwrap(
          api.PUT("/api/v1/obstacles/{id}", {
            params: { path: { id } },
            body: { type: ob.type, name: ob.name, widthMeters: geom.width, heightMeters: geom.height, rotationDegrees: geom.rotation, xMeters: geom.x, yMeters: geom.y },
          }),
        );
      }
    } catch (e) {
      setError((e as Error).message);
    }
    await refresh();
  }

  async function resizeRoomTo(width: number, height: number) {
    setError(null);
    setLayout((l) => l && { ...l, room: { ...l.room, widthMeters: width, heightMeters: height } });
    try {
      await unwrap(api.PUT("/api/v1/rooms/{id}", { params: { path: { id: room.id } }, body: { name: layout!.room.name, widthMeters: width, heightMeters: height, gridStepMeters: layout!.room.gridStepMeters } }));
    } catch (e) {
      setError((e as Error).message);
    }
    onRoomChanged();
  }

  async function duplicate() {
    if (!selection) return;
    setError(null);
    const offset = Math.max(grid, 0.5);
    try {
      if (selection.kind === "table") {
        const tb = layout!.tables.find((v) => v.table.id === selection.id)!.table;
        const created = await unwrap(
          api.POST("/api/v1/rooms/{roomId}/tables", {
            params: { path: { roomId: room.id } },
            body: { xMeters: tb.xMeters + offset, yMeters: tb.yMeters + offset, widthMeters: tb.widthMeters, heightMeters: tb.heightMeters, rotationDegrees: tb.rotationDegrees, shape: tb.shape, seats: tb.seats, accessible: tb.accessible },
          }),
        );
        await refresh();
        setSelection({ kind: "table", id: created.id });
      } else {
        const ob = layout!.obstacles.find((o) => o.id === selection.id)!;
        const created = await unwrap(
          api.POST("/api/v1/rooms/{roomId}/obstacles", {
            params: { path: { roomId: room.id } },
            body: { type: ob.type, name: ob.name, xMeters: ob.xMeters + offset, yMeters: ob.yMeters + offset, widthMeters: ob.widthMeters, heightMeters: ob.heightMeters, rotationDegrees: ob.rotationDegrees },
          }),
        );
        await refresh();
        setSelection({ kind: "obstacle", id: created.id });
      }
    } catch (e) {
      setError((e as Error).message);
    }
  }

  async function removeSelected() {
    if (!selection) return;
    if (!window.confirm(t("deleteConfirm"))) return;
    setError(null);
    try {
      if (selection.kind === "table") await expectOk(api.DELETE("/api/v1/tables/{id}", { params: { path: { id: selection.id } } }));
      else await expectOk(api.DELETE("/api/v1/obstacles/{id}", { params: { path: { id: selection.id } } }));
      setSelection(null);
      setUndo(null);
      await refresh();
    } catch (e) {
      setError((e as Error).message);
    }
  }

  const selectedTable = selection?.kind === "table" ? layout.tables.find((v) => v.table.id === selection.id) : undefined;
  const selectedObstacle = selection?.kind === "obstacle" ? layout.obstacles.find((o) => o.id === selection.id) : undefined;
  const selectedGeom = selectedTable ? geomOf(selectedTable.table) : selectedObstacle ? geomOf(selectedObstacle) : null;

  const chip = "min-h-11 rounded-full border border-line-strong bg-surface px-3.5 text-sm font-bold hover:bg-canvas disabled:opacity-40 lg:w-full lg:rounded-[10px] lg:text-left";
  const heading = "w-full text-xs font-extrabold tracking-wide text-muted uppercase";

  // The tools: a rail beside the plan on a big screen (so the plan keeps all its height), stacked above it on a phone.
  const rail = canEdit ? (
    <div className="flex flex-col gap-3 lg:w-48 lg:shrink-0">
      <section className="flex flex-col gap-3 rounded-2xl border border-line bg-surface p-3" aria-label={t("palette")}>
        <button type="button" aria-expanded={paletteOpen} onClick={() => setPaletteOpen((v) => !v)} className="flex min-h-11 items-center justify-between text-left text-sm font-extrabold lg:hidden">
          {t("palette")}
          <span aria-hidden="true">{paletteOpen ? "−" : "+"}</span>
        </button>
        <div className={`${paletteOpen ? "flex" : "hidden"} flex-col gap-3 lg:flex`}>
          <div className="flex flex-wrap items-center gap-2 lg:flex-col lg:items-stretch lg:gap-1.5">
            <span className={heading}>{t("addTable")}</span>
            {TABLE_KINDS.map((k) => (
              <button key={k.shape} type="button" className={chip} onClick={() => void addTable(k.shape, k.width, k.height)}>
                + {t(k.label)}
              </button>
            ))}
          </div>
          <div className="flex flex-wrap items-center gap-2 lg:flex-col lg:items-stretch lg:gap-1.5">
            <span className={heading}>{t("addElement")}</span>
            {OBSTACLE_TYPES.map((o) => (
              <button key={o} type="button" className={chip} onClick={() => void addElement(o)}>
                + {te(o)}
              </button>
            ))}
          </div>
        </div>
      </section>

      <div className="flex flex-wrap items-center gap-2 lg:order-first lg:flex-col lg:items-stretch lg:gap-1.5 lg:rounded-2xl lg:border lg:border-line lg:bg-surface lg:p-3" role="toolbar" aria-label={t("tools")}>
        <span className={`${heading} hidden lg:block`}>{t("tools")}</span>
        <button type="button" aria-pressed={snapOn} className={`${chip} ${snapOn ? "!border-primary !bg-primary-soft !text-primary-strong" : ""}`} onClick={() => setSnapOn((v) => !v)}>
          {t("snap")}
        </button>
        <button type="button" className={chip} disabled={!selectedGeom} onClick={() => selection && selectedGeom && void saveGeometry(selection.kind, selection.id, { ...selectedGeom, rotation: (selectedGeom.rotation + 90) % 360 })}>
          {t("rotate90")}
        </button>
        <button type="button" className={chip} disabled={!selectedGeom} onClick={() => void duplicate()}>
          {t("duplicate")}
        </button>
        <button type="button" className={`${chip} !text-danger-ink`} disabled={!selectedGeom} onClick={() => void removeSelected()}>
          {t("deleteSelected")}
        </button>
        <button
          type="button"
          className={chip}
          disabled={!undo}
          onClick={() => {
            if (!undo) return;
            const { kind, id, before } = undo;
            setUndo(null);
            void saveGeometry(kind, id, before, false);
          }}
        >
          {t("undo")}
        </button>
      </div>
    </div>
  ) : null;

  return (
    <div className={`flex flex-col gap-4 ${expanded ? "fixed inset-0 z-50 overflow-y-auto bg-canvas p-4" : ""}`}>
      {error ? (
        <p role="alert" className="rounded-lg bg-danger-soft px-3 py-2 text-sm font-semibold text-danger-ink">
          {error}
        </p>
      ) : null}

      <div className="flex flex-col gap-4 lg:flex-row lg:items-start">
        {rail}
        <div className="flex min-w-0 flex-1 flex-col gap-3">
          <FloorCanvas
            layout={layout}
            selection={selection}
            editable={canEdit}
            snapOn={snapOn}
            onSelect={setSelection}
            onGeometry={(k, id, g) => void saveGeometry(k, id, g)}
            onRoomSize={(w, h) => void resizeRoomTo(w, h)}
            onViewCenter={(c) => {
              viewCenter.current = c;
            }}
            fill={expanded}
            onToggleFill={() => setExpanded((v) => !v)}
          />
          <ul className="flex flex-wrap gap-x-4 gap-y-2 text-xs text-muted" aria-label={t("live")}>
            {LIVE_STATUSES.map((s) => (
              <li key={s} className="flex items-center gap-1.5">
                <span className="h-3.5 w-3.5 rounded border-2" style={{ background: liveSwatch(s).fill, borderColor: liveSwatch(s).stroke }} aria-hidden="true" />
                {t(`live${s}`)}
              </li>
            ))}
          </ul>
          <p className="text-xs text-muted">
            {t("panHint")}
            {canEdit ? ` ${t("dragHint")}` : ""}
          </p>
        </div>

        {selectedTable ? (
          <TableInspector
            key={JSON.stringify(selectedTable)}
            table={selectedTable.table}
            live={selectedTable.liveStatus}
            canEdit={canEdit}
            onChanged={() => void refresh()}
            onDeleted={() => {
              setSelection(null);
              void refresh();
            }}
          />
        ) : selectedObstacle ? (
          <ObstacleInspector
            key={JSON.stringify(selectedObstacle)}
            obstacle={selectedObstacle}
            canEdit={canEdit}
            onChanged={() => void refresh()}
            onDeleted={() => {
              setSelection(null);
              void refresh();
            }}
          />
        ) : (
          <EmptyInspector />
        )}
      </div>
    </div>
  );
}
