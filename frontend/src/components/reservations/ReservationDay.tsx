"use client";

import { useTranslations } from "next-intl";
import { useCallback, useMemo, useState } from "react";
import { api } from "@/api/client";
import { expectOk, unwrap } from "@/api/call";
import type { components } from "@/api/schema";
import { Badge } from "@/components/ui/Badge";
import { Button } from "@/components/ui/Button";
import { Dialog } from "@/components/ui/Dialog";
import { useOnReservationsChanged } from "@/lib/reservationsChanged";
import { useRemote } from "@/lib/useRemote";
import { ReservationForm } from "./ReservationForm";
import { endTime, timeOf } from "./time";

type Reservation = components["schemas"]["Reservation"];
type Status = Reservation["status"];

const TONE: Record<Status, "neutral" | "good" | "warn" | "danger" | "info"> = {
  PENDING: "neutral",
  CONFIRMED: "good",
  ARRIVED: "info",
  COMPLETED: "neutral",
  CANCELLED: "danger",
  NO_SHOW: "warn",
};

const FILTERS: (Status | "ALL")[] = ["ALL", "PENDING", "CONFIRMED", "ARRIVED", "NO_SHOW", "CANCELLED"];

/** One day of reservations. Mount it with a key per date so the old day never lingers while the next loads. */
export function ReservationDay({ date, canEdit, creating, onCloseCreate }: { date: string; canEdit: boolean; creating: boolean; onCloseCreate: () => void }) {
  const t = useTranslations("reservations");
  const ts = useTranslations("reservationStatus");
  const ta = useTranslations("allergens");
  const [filter, setFilter] = useState<Status | "ALL">("ALL");
  const [editing, setEditing] = useState<Reservation | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(
    () => unwrap(api.GET("/api/v1/reservations", { params: { query: { date } } })),
    [date],
  );
  const list = useRemote(load);
  // A booking made or cancelled through the assistant shows up at once.
  useOnReservationsChanged(list.reload);
  const loadTables = useCallback(() => unwrap(api.GET("/api/v1/tables")), []);
  const tables = useRemote(loadTables);
  const numbers = useMemo(() => new Map((tables.data ?? []).map((tb) => [tb.id, tb.number])), [tables.data]);

  const shown = (list.data ?? []).filter((r) => filter === "ALL" || r.status === filter);

  async function setStatus(r: Reservation, status: Status) {
    setError(null);
    try {
      await unwrap(api.PATCH("/api/v1/reservations/{id}/status", { params: { path: { id: r.id } }, body: { status } }));
      list.reload();
    } catch (e) {
      setError((e as Error).message);
    }
  }

  async function remove(r: Reservation) {
    if (!window.confirm(t("deleteConfirm", { name: r.guestName }))) return;
    try {
      await expectOk(api.DELETE("/api/v1/reservations/{id}", { params: { path: { id: r.id } } }));
      list.reload();
    } catch (e) {
      setError((e as Error).message);
    }
  }

  function saved() {
    setEditing(null);
    onCloseCreate();
    list.reload();
  }

  return (
    <>
      <div className="flex flex-wrap gap-2" role="group" aria-label={t("status")}>
        {FILTERS.map((f) => (
          <button
            key={f}
            type="button"
            aria-pressed={filter === f}
            onClick={() => setFilter(f)}
            className={`min-h-11 rounded-full border px-4 text-sm font-bold ${
              filter === f ? "border-primary bg-primary-soft text-primary-strong" : "border-line bg-surface text-muted"
            }`}
          >
            {f === "ALL" ? t("filterAll") : ts(f)}
          </button>
        ))}
      </div>

      {error ? (
        <p role="alert" className="rounded-lg bg-danger-soft px-3 py-2 text-sm font-semibold text-danger-ink">
          {error}
        </p>
      ) : null}

      {list.loading && !list.data ? <p className="text-muted">…</p> : null}
      {list.data && shown.length === 0 ? <p className="rounded-2xl border border-dashed border-line-strong p-8 text-center text-muted">{t("empty")}</p> : null}

      <ul className="flex flex-col gap-3">
        {shown.map((r) => (
          <li key={r.id} className="flex flex-col gap-3 rounded-2xl border border-line bg-surface p-4 sm:flex-row sm:items-center">
            <div className="flex items-baseline gap-4 sm:w-64">
              <span className="flex flex-col leading-tight">
                <span className="text-2xl font-extrabold tabular-nums">{timeOf(r.startsAt)}</span>
                <span className="text-sm font-semibold text-muted tabular-nums">– {endTime(r.startsAt, r.durationMinutes)}</span>
              </span>
              <div className="flex min-w-0 flex-col">
                <span className="truncate text-base font-bold">{r.guestName}</span>
                <span className="text-sm text-muted">{t("guests", { count: r.partySize })}</span>
              </div>
            </div>
            <div className="flex flex-1 flex-wrap items-center gap-2">
              <Badge tone={TONE[r.status]}>{ts(r.status)}</Badge>
              {r.tableId && numbers.has(r.tableId) ? <Badge tone="neutral">{t("tableSeats", { number: numbers.get(r.tableId)!, seats: tables.data?.find((x) => x.id === r.tableId)?.seats ?? 0 })}</Badge> : <Badge tone="warn">{t("unassignedWarning")}</Badge>}
              {r.allergens.length > 0 ? <Badge tone="danger">{t("allergyLabel", { list: r.allergens.map((a) => ta(a)).join(", ") })}</Badge> : null}
              {r.highChairs > 0 ? <Badge tone="warn">{t("highChairsCount", { count: r.highChairs })}</Badge> : null}
              {r.strollerSpaces > 0 ? <Badge tone="warn">{t("strollersCount", { count: r.strollerSpaces })}</Badge> : null}
              {r.notes ? <span className="w-full text-sm text-muted">{r.notes}</span> : null}
            </div>
            <div className="flex flex-wrap gap-2">
              {r.status === "PENDING" || r.status === "CONFIRMED" ? (
                <>
                  <Button onClick={() => void setStatus(r, "ARRIVED")}>{t("markArrived")}</Button>
                  <Button variant="secondary" onClick={() => void setStatus(r, "NO_SHOW")}>{t("markNoShow")}</Button>
                </>
              ) : null}
              {r.status === "ARRIVED" ? <Button variant="secondary" onClick={() => void setStatus(r, "COMPLETED")}>{t("markCompleted")}</Button> : null}
              {canEdit ? (
                <>
                  <Button variant="secondary" onClick={() => setEditing(r)}>{t("edit")}</Button>
                  <Button variant="danger" onClick={() => void remove(r)}>{t("delete")}</Button>
                </>
              ) : null}
            </div>
          </li>
        ))}
      </ul>

      <Dialog open={creating} onClose={onCloseCreate} title={t("new")}>
        <ReservationForm defaultDate={date} onSaved={saved} onCancel={onCloseCreate} />
      </Dialog>
      <Dialog open={editing !== null} onClose={() => setEditing(null)} title={t("edit")}>
        {editing ? <ReservationForm existing={editing} defaultDate={date} onSaved={saved} onCancel={() => setEditing(null)} /> : null}
      </Dialog>
    </>
  );
}
