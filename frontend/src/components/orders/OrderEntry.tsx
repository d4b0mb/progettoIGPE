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
import { groupBySection } from "@/components/menu/sections";
import { formatCents } from "@/components/menu/money";
import { todayLocal } from "@/components/reservations/time";
import { addLine, isPlain, lineCents, newLineId, nudgePlain, quantityOf, removeLine, replaceLine, type CartLine } from "./cart";
import { CustomizeDialog, type Choice } from "./CustomizeDialog";
import { useRemote } from "@/lib/useRemote";

type TableView = components["schemas"]["TableView"];
type MenuItem = components["schemas"]["MenuItem"];
type Modifier = components["schemas"]["Modifier"];
type Allergen = MenuItem["allergens"][number];
type Priority = components["schemas"]["OrderRequest"]["priority"];

/** Everything the screen needs to start an order: tables with their live status, the menu, and who is seated where. */
async function loadSetup() {
  const rooms = await unwrap(api.GET("/api/v1/rooms"));
  const layouts = await Promise.all(rooms.map((r) => unwrap(api.GET("/api/v1/rooms/{id}/layout", { params: { path: { id: r.id } } }))));
  const tables: TableView[] = layouts.flatMap((l) => l.tables).sort((a, b) => a.table.number - b.table.number);
  const menu = await unwrap(api.GET("/api/v1/menu/items"));
  const options = await unwrap(api.GET("/api/v1/menu/modifiers"));
  const today = await unwrap(api.GET("/api/v1/reservations", { params: { query: { date: todayLocal() } } }));
  // Parties that have arrived and not left: their declared allergies apply to anything ordered at their table.
  const allergiesByTable = new Map<string, Allergen[]>();
  for (const r of today) {
    if (r.status === "ARRIVED" && r.tableId && r.allergens.length > 0) allergiesByTable.set(r.tableId, r.allergens);
  }
  return { tables, menu, options, allergiesByTable };
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
  const [lines, setLines] = useState<CartLine[]>([]);
  // The dish being customised: a new line for it, or an existing line being edited.
  const [customising, setCustomising] = useState<{ item: MenuItem; line?: CartLine } | null>(null);
  const [servings, setServings] = useState<number[]>([1]);
  const [active, setActive] = useState(1);
  const [search, setSearch] = useState("");
  const [courseFilter, setCourseFilter] = useState<"ALL" | (typeof COURSES)[number]>("ALL");
  const [priority, setPriority] = useState<NonNullable<Priority>>("NORMAL");
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const tableAllergies = useMemo(() => setup.data?.allergiesByTable.get(tableId) ?? [], [setup.data, tableId]);
  const menu = setup.data?.menu ?? [];
  const options: Modifier[] = setup.data?.options ?? [];
  const optionById = new Map(options.map((o) => [o.id, o]));
  const optionCents = Object.fromEntries(options.map((o) => [o.id, o.priceCents]));
  const itemById = new Map(menu.map((m) => [m.id, m]));
  const optionsFor = (m: MenuItem) => options.filter((o) => o.course === m.course);
  // The order in the sequence it was entered: serving by serving.
  const ordered = [...lines].sort((a, b) => a.serving - b.serving);
  const count = lines.reduce((sum, l) => sum + l.quantity, 0);
  const countIn = (serving: number) => lines.filter((l) => l.serving === serving).reduce((sum, l) => sum + l.quantity, 0);
  // What is really on the plate: the dish's allergens plus those of the chosen options.
  const allergensOfLine = (l: CartLine) => [...new Set([...(itemById.get(l.itemId)?.allergens ?? []), ...l.modifierIds.flatMap((id) => optionById.get(id)?.allergens ?? [])])];
  const conflictsOfLine = (l: CartLine) => allergensOfLine(l).filter((a) => tableAllergies.includes(a));
  const conflictsOf = (m: MenuItem) => m.allergens.filter((a) => tableAllergies.includes(a));
  const conflicting = lines.flatMap(conflictsOfLine);
  const totalCents = lines.reduce((sum, l) => sum + lineCents(l, itemById.get(l.itemId)?.priceCents, optionCents), 0);

  function change(id: string, delta: number) {
    setLines((l) => nudgePlain(l, id, active, delta));
  }

  function saveChoice(choice: Choice) {
    if (!customising) return;
    const { item, line } = customising;
    setLines((l) => (line ? replaceLine(l, line.id, choice) : addLine(l, { id: newLineId(), itemId: item.id, serving: active, ...choice })));
    setCustomising(null);
  }

  function addServing() {
    const next = Math.min(9, Math.max(...servings) + 1);
    if (servings.includes(next)) return;
    setServings((s) => [...s, next]);
    setActive(next);
  }

  function removeServing(n: number) {
    setServings((s) => s.filter((x) => x !== n));
    if (active === n) setActive(servings.find((x) => x !== n) ?? 1);
  }

  async function send() {
    // Pressing Send always answers: say what is missing instead of leaving a dead button.
    if (tableId === "") {
      setError(t("errNoTable"));
      return;
    }
    if (count === 0) {
      setError(t("errEmptyOrder"));
      return;
    }
    if (conflicting.length > 0 && !window.confirm(t("confirmAllergy", { list: [...new Set(conflicting)].map((a) => ta(a)).join(", ") }))) return;
    setBusy(true);
    setError(null);
    try {
      const ticket = await unwrap(
        api.POST("/api/v1/orders", {
          body: { tableId, priority, note: note.trim() || undefined, items: ordered.map((l) => ({ menuItemId: l.itemId, quantity: l.quantity, serving: l.serving, modifierIds: l.modifierIds, note: l.note.trim() || undefined })) },
        }),
      );
      setLines([]);
      setServings([1]);
      setActive(1);
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
  const needle = search.trim().toLowerCase();
  const matches = (m: MenuItem) =>
    (courseFilter === "ALL" || m.course === courseFilter) && (needle === "" || m.name.toLowerCase().includes(needle) || m.description.toLowerCase().includes(needle));

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
              onClick={() => {
                setTableId(table.id);
                setError(null);
              }}
              className={`flex min-h-14 flex-col items-center justify-center rounded-xl border-2 text-lg font-extrabold ${
                tableId === table.id
                  ? "border-primary bg-primary text-on-primary"
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

      <section className="flex flex-col gap-2" aria-label={t("servingTabs")}>
        <div role="group" aria-label={t("servingTabs")} className="flex flex-wrap items-center gap-2">
          {servings.map((n) => (
            <span key={n} className="flex items-stretch">
              <button
                type="button"
                aria-pressed={active === n}
                onClick={() => setActive(n)}
                className={`min-h-12 rounded-l-[10px] border-2 px-4 text-sm font-extrabold ${n === 1 && servings.length === 1 ? "rounded-r-[10px]" : ""} ${
                  active === n ? "border-primary bg-primary text-on-primary" : "border-line-strong bg-surface text-ink"
                }`}
              >
                {t("serving", { n })}
                {countIn(n) > 0 ? <span className="ml-2 rounded-full bg-white/25 px-2 py-0.5 text-xs tabular-nums">{countIn(n)}</span> : null}
              </button>
              {servings.length > 1 && countIn(n) === 0 ? (
                <button type="button" aria-label={t("removeServing", { n })} onClick={() => removeServing(n)} className="min-h-12 min-w-10 rounded-r-[10px] border-2 border-l-0 border-line-strong bg-surface text-lg font-bold text-muted">
                  ×
                </button>
              ) : null}
            </span>
          ))}
          {servings.length < 9 ? (
            <button type="button" onClick={addServing} className="min-h-12 rounded-[10px] border-2 border-dashed border-line-strong px-4 text-sm font-bold text-primary-strong">
              + {t("addServing")}
            </button>
          ) : null}
        </div>
        {servings.length > 1 ? <p className="text-sm text-muted">{t("servingHint")}</p> : null}
      </section>

      <section className="flex flex-col gap-2" aria-label={t("search")}>
        <Field label={t("search")} type="search" value={search} onChange={(e) => setSearch(e.target.value)} />
        <div role="group" aria-label={t("search")} className="flex flex-wrap gap-2">
          {(["ALL", ...COURSES] as const)
            .filter((c) => c === "ALL" || menu.some((m) => m.course === c))
            .map((c) => (
              <button
                key={c}
                type="button"
                aria-pressed={courseFilter === c}
                onClick={() => setCourseFilter(c)}
                className={`min-h-11 rounded-full border px-4 text-sm font-bold ${courseFilter === c ? "border-primary bg-primary text-on-primary" : "border-line-strong bg-surface text-ink"}`}
              >
                {c === "ALL" ? t("allCourses") : tcourse(c)}
              </button>
            ))}
        </div>
      </section>

      {menu.length === 0 ? <p className="text-muted">{t("menuEmpty")}</p> : null}
      {menu.length > 0 && !menu.some((m) => matches(m)) ? <p className="text-muted">{t("noMatches")}</p> : null}
      {COURSES.map((course) => {
        const items = menu.filter((m) => m.course === course && matches(m));
        if (items.length === 0) return null;
        return (
          <section key={course} className="flex flex-col gap-3" aria-label={tcourse(course)}>
            <h2 className="text-sm font-extrabold tracking-wide text-muted uppercase">{tcourse(course)}</h2>
            {groupBySection(items).map((group) => (
              <div key={group.section} className="flex flex-col gap-2">
                {group.section ? <h3 className="text-xs font-extrabold tracking-wide text-primary-strong uppercase">{group.section}</h3> : null}
                <ul className="flex flex-col gap-2">
                  {group.items.map((m) => {
                    const conflicts = conflictsOf(m);
                    const plain = lines.find((l) => l.itemId === m.id && l.serving === active && isPlain(l));
                    const qty = plain?.quantity ?? 0;
                    const custom = lines.filter((l) => l.itemId === m.id && l.serving === active && !isPlain(l));
                    const elsewhere = servings.filter((n) => n !== active && quantityOf(lines, m.id, n) > 0);
                    return (
                      <li key={m.id} className={`flex flex-col gap-2 rounded-2xl border-2 bg-surface p-3 ${conflicts.length > 0 ? "border-danger" : "border-line"} ${m.available ? "" : "opacity-50"}`}>
                        <div className="flex items-center gap-3">
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
                              {elsewhere.map((n) => (
                                <Badge key={n} tone="info">
                                  {quantityOf(lines, m.id, n)} × {t("serving", { n })}
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
                            <button type="button" aria-label={t("add", { name: m.name })} disabled={!m.available} onClick={() => change(m.id, 1)} className="min-h-12 min-w-12 rounded-[10px] bg-primary text-xl font-bold text-on-primary disabled:opacity-40">
                              +
                            </button>
                          </div>
                        </div>
                        {m.available ? (
                          <div>
                            <button
                              type="button"
                              aria-label={t("customiseDish", { name: m.name })}
                              onClick={() => setCustomising({ item: m })}
                              className="min-h-11 rounded-full border border-dashed border-line-strong px-4 text-sm font-bold text-primary-strong hover:bg-canvas"
                            >
                              {optionsFor(m).length > 0 ? t("customise") : t("addNote")}
                            </button>
                          </div>
                        ) : null}
                        {custom.length > 0 ? (
                          <ul className="flex flex-col gap-1.5" aria-label={t("customLines", { name: m.name })}>
                            {custom.map((l) => (
                              <li key={l.id} className="flex flex-wrap items-center gap-2 rounded-xl bg-primary-soft px-3 py-2 text-sm">
                                <span className="font-extrabold tabular-nums">{l.quantity} ×</span>
                                <span className="min-w-0 flex-1">
                                  {options.filter((o) => l.modifierIds.includes(o.id)).map((o) => o.name).join(" · ")}
                                  {l.note ? <span className="block font-semibold text-muted">“{l.note}”</span> : null}
                                  {conflictsOfLine(l).length > 0 ? <span className="block text-xs font-extrabold text-danger-ink">{t("containsAllergen", { list: conflictsOfLine(l).map((a) => ta(a)).join(", ") })}</span> : null}
                                </span>
                                <button type="button" onClick={() => setCustomising({ item: m, line: l })} className="min-h-11 rounded-lg px-3 text-sm font-bold text-primary-strong underline">
                                  {t("editLine")}
                                </button>
                                <button type="button" aria-label={t("removeLine")} onClick={() => setLines((all) => removeLine(all, l.id))} className="min-h-11 min-w-11 rounded-lg text-lg font-bold text-danger-ink">
                                  ×
                                </button>
                              </li>
                            ))}
                          </ul>
                        ) : null}
                      </li>
                    );
                  })}
                </ul>
              </div>
            ))}
          </section>
        );
      })}

      {customising ? (
        <CustomizeDialog
          key={customising.line?.id ?? customising.item.id}
          item={customising.item}
          options={optionsFor(customising.item)}
          initial={customising.line ?? { modifierIds: [], note: "", quantity: 1 }}
          editing={customising.line !== undefined}
          tableAllergies={tableAllergies}
          onSave={saveChoice}
          onClose={() => setCustomising(null)}
        />
      ) : null}

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
          <Button className="min-h-14 min-w-52 flex-1 text-base sm:flex-none" disabled={busy} onClick={() => void send()}>
            {busy ? t("sending") : `${t("send")} · ${t("summary", { count })}${totalCents > 0 ? ` · ${formatCents(totalCents, locale)}` : ""}`}
          </Button>
        </div>
      </div>
    </div>
  );
}
