"use client";

import { useLocale, useTranslations } from "next-intl";
import { useMemo, useState } from "react";
import { api } from "@/api/client";
import { unwrap } from "@/api/call";
import type { components } from "@/api/schema";
import { Badge } from "@/components/ui/Badge";
import { Button } from "@/components/ui/Button";
import { SelectField } from "@/components/ui/Controls";
import { Field } from "@/components/ui/Field";
import { COURSES } from "@/components/menu/MenuForm";
import { formatCents } from "@/components/menu/money";
import { todayLocal } from "@/components/reservations/time";
import { useRemote } from "@/lib/useRemote";

type TableView = components["schemas"]["TableView"];
type MenuItem = components["schemas"]["MenuItem"];
type Allergen = MenuItem["allergens"][number];
type Priority = components["schemas"]["OrderRequest"]["priority"];

/** Everything the screen needs to start an order: tables with their live status, the menu, and who is seated where. */
async function loadSetup() {
  const rooms = await unwrap(api.GET("/api/v1/rooms"));
  const layouts = await Promise.all(rooms.map((r) => unwrap(api.GET("/api/v1/rooms/{id}/layout", { params: { path: { id: r.id } } }))));
  const tables: TableView[] = layouts.flatMap((l) => l.tables).sort((a, b) => a.table.number - b.table.number);
  const menu = await unwrap(api.GET("/api/v1/menu/items"));
  const today = await unwrap(api.GET("/api/v1/reservations", { params: { query: { date: todayLocal() } } }));
  // Parties that have arrived and not left: their declared allergies apply to anything ordered at their table.
  const allergiesByTable = new Map<string, Allergen[]>();
  for (const r of today) {
    if (r.status === "ARRIVED" && r.tableId && r.allergens.length > 0) allergiesByTable.set(r.tableId, r.allergens);
  }
  return { tables, menu, allergiesByTable };
}

/**
 * Taking an order on a phone: pick the table, tap dishes, send. If the party at that
 * table declared allergies, dishes that contain them are flagged before anything is sent.
 */
export function OrderEntry({ onSent }: { onSent: (code: string) => void }) {
  const t = useTranslations("orders");
  const tl = useTranslations("floor");
  const ta = useTranslations("allergens");
  const tcourse = useTranslations("courses");
  const tp = useTranslations("priority");
  const locale = useLocale();

  const setup = useRemote(loadSetup);

  const [tableId, setTableId] = useState("");
  const [cart, setCart] = useState<Record<string, number>>({});
  const [priority, setPriority] = useState<NonNullable<Priority>>("NORMAL");
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const tableAllergies = useMemo(() => setup.data?.allergiesByTable.get(tableId) ?? [], [setup.data, tableId]);
  const menu = setup.data?.menu ?? [];
  const lines = menu.filter((m) => (cart[m.id] ?? 0) > 0);
  const count = lines.reduce((sum, m) => sum + cart[m.id], 0);
  const conflictsOf = (m: MenuItem) => m.allergens.filter((a) => tableAllergies.includes(a));
  const conflicting = lines.flatMap(conflictsOf);

  function change(id: string, delta: number) {
    setCart((c) => {
      const next = Math.max(0, Math.min(99, (c[id] ?? 0) + delta));
      const others = Object.entries(c).filter(([key]) => key !== id);
      return Object.fromEntries(next === 0 ? others : [...others, [id, next]]);
    });
  }

  async function send() {
    if (conflicting.length > 0 && !window.confirm(t("confirmAllergy", { list: [...new Set(conflicting)].map((a) => ta(a)).join(", ") }))) return;
    setBusy(true);
    setError(null);
    try {
      const ticket = await unwrap(
        api.POST("/api/v1/orders", {
          body: { tableId, priority, note: note.trim() || undefined, items: lines.map((m) => ({ menuItemId: m.id, quantity: cart[m.id] })) },
        }),
      );
      setCart({});
      setNote("");
      setPriority("NORMAL");
      onSent(ticket.code);
    } catch (e) {
      setError((e as Error).message);
    }
    setBusy(false);
  }

  if (!setup.data) return <p className="text-muted">…</p>;
  const { tables } = setup.data;
  const ready = tableId !== "" && count > 0;

  return (
    <div className="flex flex-col gap-5 pb-40 md:pb-24">
      <section className="flex flex-col gap-2" aria-label={t("chooseTable")}>
        <h2 className="text-sm font-extrabold tracking-wide text-muted uppercase">{t("chooseTable")}</h2>
        <div className="grid grid-cols-4 gap-2 sm:grid-cols-6 lg:grid-cols-8">
          {tables.map(({ table, liveStatus }) => (
            <button
              key={table.id}
              type="button"
              aria-pressed={tableId === table.id}
              aria-label={t("tableLive", { number: table.number, status: tl(`live${liveStatus}`) })}
              onClick={() => setTableId(table.id)}
              className={`flex min-h-14 flex-col items-center justify-center rounded-xl border-2 text-lg font-extrabold ${
                tableId === table.id
                  ? "border-primary bg-primary text-white"
                  : liveStatus === "OCCUPIED"
                    ? "border-primary bg-primary-soft text-primary-strong"
                    : liveStatus === "RESERVED"
                      ? "border-amber-600 bg-warn-soft text-warn-ink"
                      : "border-line-strong bg-surface text-ink"
              }`}
            >
              {table.number}
              <span className="text-[10px] font-semibold opacity-80">{t("seatsShort", { count: table.seats })}</span>
            </button>
          ))}
        </div>
        {tables.length > 0 && tableId === "" ? <p className="text-sm text-muted">{t("noTable")}</p> : null}
        {tableAllergies.length > 0 ? (
          <p role="alert" className="rounded-lg bg-danger-soft px-3 py-2 text-sm font-extrabold text-danger-ink">
            {t("tableAllergy", { list: tableAllergies.map((a) => ta(a)).join(", ") })}
          </p>
        ) : null}
      </section>

      {menu.length === 0 ? <p className="text-muted">{t("menuEmpty")}</p> : null}
      {COURSES.map((course) => {
        const items = menu.filter((m) => m.course === course);
        if (items.length === 0) return null;
        return (
          <section key={course} className="flex flex-col gap-2" aria-label={tcourse(course)}>
            <h2 className="text-sm font-extrabold tracking-wide text-muted uppercase">{tcourse(course)}</h2>
            <ul className="flex flex-col gap-2">
              {items.map((m) => {
                const conflicts = conflictsOf(m);
                const qty = cart[m.id] ?? 0;
                return (
                  <li key={m.id} className={`flex items-center gap-3 rounded-2xl border-2 bg-surface p-3 ${conflicts.length > 0 ? "border-danger" : "border-line"} ${m.available ? "" : "opacity-50"}`}>
                    <div className="flex min-w-0 flex-1 flex-col gap-1">
                      <span className="font-bold">{m.name}</span>
                      <div className="flex flex-wrap items-center gap-1.5">
                        {m.priceCents != null ? <span className="text-xs font-semibold text-muted tabular-nums">{formatCents(m.priceCents, locale)}</span> : null}
                        {!m.available ? <Badge tone="warn">{t("unavailable")}</Badge> : null}
                        {m.allergens.map((a) => (
                          <Badge key={a} tone={conflicts.includes(a) ? "danger" : "neutral"}>
                            {ta(a)}
                          </Badge>
                        ))}
                      </div>
                      {conflicts.length > 0 ? <span className="text-xs font-bold text-danger-ink">{t("containsAllergen", { list: conflicts.map((a) => ta(a)).join(", ") })}</span> : null}
                    </div>
                    <div className="flex items-center gap-1.5">
                      <button type="button" aria-label={t("remove", { name: m.name })} disabled={qty === 0} onClick={() => change(m.id, -1)} className="min-h-12 min-w-12 rounded-[10px] border border-line-strong bg-surface text-xl font-bold disabled:opacity-40">
                        −
                      </button>
                      <span aria-label={t("quantity", { name: m.name })} className="min-w-7 text-center text-lg font-extrabold tabular-nums">
                        {qty}
                      </span>
                      <button type="button" aria-label={t("add", { name: m.name })} disabled={!m.available} onClick={() => change(m.id, 1)} className="min-h-12 min-w-12 rounded-[10px] bg-primary text-xl font-bold text-white disabled:opacity-40">
                        +
                      </button>
                    </div>
                  </li>
                );
              })}
            </ul>
          </section>
        );
      })}

      <div className="fixed inset-x-0 bottom-14 z-10 flex flex-col gap-3 border-t border-line bg-surface p-3 shadow-[0_-4px_12px_rgba(0,0,0,0.06)] md:bottom-0 md:left-60 md:px-8">
        {error ? (
          <p role="alert" className="rounded-lg bg-danger-soft px-3 py-2 text-sm font-semibold text-danger-ink">
            {error}
          </p>
        ) : null}
        <div className="flex flex-wrap items-end gap-3">
          <div className="w-32">
            <SelectField label={t("priority")} value={priority} onChange={(e) => setPriority(e.target.value as typeof priority)}>
              {(["NORMAL", "HIGH", "URGENT", "VIP"] as const).map((p) => (
                <option key={p} value={p}>
                  {tp(p)}
                </option>
              ))}
            </SelectField>
          </div>
          <div className="min-w-40 flex-1">
            <Field label={t("note")} value={note} onChange={(e) => setNote(e.target.value)} maxLength={200} />
          </div>
          <Button className="min-h-14 min-w-52 flex-1 text-base sm:flex-none" disabled={!ready || busy} onClick={() => void send()}>
            {busy ? t("sending") : `${t("send")} · ${t("summary", { count })}`}
          </Button>
        </div>
      </div>
    </div>
  );
}
