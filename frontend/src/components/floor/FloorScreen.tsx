"use client";

import { useTranslations } from "next-intl";
import { useCallback, useEffect, useState } from "react";
import { api } from "@/api/client";
import { expectOk, unwrap } from "@/api/call";
import type { components } from "@/api/schema";
import { useSession } from "@/api/session";
import { Button } from "@/components/ui/Button";
import { Dialog } from "@/components/ui/Dialog";
import { useRemote } from "@/lib/useRemote";
import { FloorCanvas, LIVE_STATUSES, liveSwatch, type Selection } from "./FloorCanvas";
import { defaultObstacleSize, type ObstacleType } from "./geometry";
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
        <RoomView key={room.id} room={room} canEdit={canEdit} />
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

/** One room: the drawing, plus the details of whatever is selected. */
function RoomView({ room, canEdit }: { room: Room; canEdit: boolean }) {
  const t = useTranslations("floor");
  const te = useTranslations("elements");
  const [layout, setLayout] = useState<Layout | null>(null);
  const [selection, setSelection] = useState<Selection>(null);
  const [error, setError] = useState<string | null>(null);
  const [newType, setNewType] = useState<ObstacleType>("WALL");

  const fetchLayout = useCallback(() => unwrap(api.GET("/api/v1/rooms/{id}/layout", { params: { path: { id: room.id } } })), [room.id]);

  useEffect(() => {
    let cancelled = false;
    fetchLayout().then(
      (l) => !cancelled && setLayout(l),
      (e: Error) => !cancelled && setError(e.message),
    );
    return () => {
      cancelled = true;
    };
  }, [fetchLayout]);

  const refresh = useCallback(async () => {
    try {
      setLayout(await fetchLayout());
    } catch (e) {
      setError((e as Error).message);
    }
  }, [fetchLayout]);

  if (!layout) return <p className="text-muted">…</p>;

  async function addTable() {
    setError(null);
    try {
      const created = await unwrap(
        api.POST("/api/v1/rooms/{roomId}/tables", {
          params: { path: { roomId: room.id } },
          body: { xMeters: room.widthMeters / 2, yMeters: room.heightMeters / 2, widthMeters: 1.2, heightMeters: 0.8, shape: "RECTANGLE" },
        }),
      );
      await refresh();
      setSelection({ kind: "table", id: created.id });
    } catch (e) {
      setError((e as Error).message);
    }
  }

  async function addElement() {
    setError(null);
    const size = defaultObstacleSize(newType);
    try {
      const created = await unwrap(
        api.POST("/api/v1/rooms/{roomId}/obstacles", {
          params: { path: { roomId: room.id } },
          body: { type: newType, xMeters: room.widthMeters / 2, yMeters: room.heightMeters / 2, widthMeters: size.width, heightMeters: size.height },
        }),
      );
      await refresh();
      setSelection({ kind: "obstacle", id: created.id });
    } catch (e) {
      setError((e as Error).message);
    }
  }

  /** Drag or keyboard move: show the new position at once, save it, then reload the truth from the server. */
  async function move(kind: "table" | "obstacle", id: string, x: number, y: number) {
    setError(null);
    setLayout((l) =>
      l && {
        ...l,
        tables: l.tables.map((v) => (kind === "table" && v.table.id === id ? { ...v, table: { ...v.table, xMeters: x, yMeters: y } } : v)),
        obstacles: l.obstacles.map((o) => (kind === "obstacle" && o.id === id ? { ...o, xMeters: x, yMeters: y } : o)),
      },
    );
    try {
      if (kind === "table") {
        const tb = layout!.tables.find((v) => v.table.id === id)!.table;
        await unwrap(
          api.PUT("/api/v1/tables/{id}", {
            params: { path: { id } },
            body: { number: tb.number, name: tb.name, seats: tb.seats, shape: tb.shape, widthMeters: tb.widthMeters, heightMeters: tb.heightMeters, rotationDegrees: tb.rotationDegrees, accessible: tb.accessible, xMeters: x, yMeters: y },
          }),
        );
      } else {
        const ob = layout!.obstacles.find((o) => o.id === id)!;
        await unwrap(
          api.PUT("/api/v1/obstacles/{id}", {
            params: { path: { id } },
            body: { type: ob.type, name: ob.name, widthMeters: ob.widthMeters, heightMeters: ob.heightMeters, rotationDegrees: ob.rotationDegrees, xMeters: x, yMeters: y },
          }),
        );
      }
    } catch (e) {
      setError((e as Error).message);
    }
    await refresh();
  }

  const selectedTable = selection?.kind === "table" ? layout.tables.find((v) => v.table.id === selection.id) : undefined;
  const selectedObstacle = selection?.kind === "obstacle" ? layout.obstacles.find((o) => o.id === selection.id) : undefined;

  return (
    <div className="flex flex-col gap-4">
      {canEdit ? (
        <div className="flex flex-wrap items-end gap-3">
          <Button onClick={() => void addTable()}>{t("addTable")}</Button>
          <div className="flex items-end gap-2">
            <div className="flex flex-col gap-1">
              <label htmlFor="new-element" className="sr-only">
                {t("elementType")}
              </label>
              <select
                id="new-element"
                value={newType}
                onChange={(e) => setNewType(e.target.value as ObstacleType)}
                className="min-h-11 rounded-[10px] border border-line-strong bg-surface px-3 text-sm font-semibold"
              >
                {OBSTACLE_TYPES.map((o) => (
                  <option key={o} value={o}>
                    {te(o)}
                  </option>
                ))}
              </select>
            </div>
            <Button variant="secondary" onClick={() => void addElement()}>
              {t("addElement")}
            </Button>
          </div>
        </div>
      ) : null}

      {error ? (
        <p role="alert" className="rounded-lg bg-danger-soft px-3 py-2 text-sm font-semibold text-danger-ink">
          {error}
        </p>
      ) : null}

      <div className="flex flex-col gap-4 lg:flex-row lg:items-start">
        <div className="flex min-w-0 flex-1 flex-col gap-3">
          <FloorCanvas layout={layout} selection={selection} editable={canEdit} onSelect={setSelection} onMove={(k, id, x, y) => void move(k, id, x, y)} />
          <ul className="flex flex-wrap gap-x-4 gap-y-2 text-xs text-muted" aria-label={t("live")}>
            {LIVE_STATUSES.map((s) => (
              <li key={s} className="flex items-center gap-1.5">
                <span className="h-3.5 w-3.5 rounded border-2" style={{ background: liveSwatch(s).fill, borderColor: liveSwatch(s).stroke }} aria-hidden="true" />
                {t(`live${s}`)}
              </li>
            ))}
          </ul>
          {canEdit ? <p className="text-xs text-muted">{t("dragHint")}</p> : null}
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
