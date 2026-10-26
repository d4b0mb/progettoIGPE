"use client";

import { useLocale, useTranslations } from "next-intl";
import { useState } from "react";
import type { components } from "@/api/schema";
import { groupBySection } from "@/components/menu/sections";
import { formatCents } from "@/components/menu/money";
import { Badge } from "@/components/ui/Badge";
import { Button } from "@/components/ui/Button";
import { Dialog } from "@/components/ui/Dialog";
import { Field } from "@/components/ui/Field";
import { MAX_QUANTITY } from "./cart";

type MenuItem = components["schemas"]["MenuItem"];
type Modifier = components["schemas"]["Modifier"];
type Allergen = MenuItem["allergens"][number];

export interface Choice {
  modifierIds: string[];
  note: string;
  quantity: number;
}

/**
 * Options and a free note for one dish: tick the toppings or removals that exist for this kind of dish, and write
 * anything else ("senza pepe", "con ghiaccio"). Any dish can have a note, even one with no options.
 */
export function CustomizeDialog({
  item,
  options,
  initial,
  editing,
  tableAllergies,
  onSave,
  onClose,
}: {
  item: MenuItem;
  options: Modifier[];
  initial: Choice;
  editing: boolean;
  tableAllergies: Allergen[];
  onSave: (choice: Choice) => void;
  onClose: () => void;
}) {
  const t = useTranslations("orders");
  const ta = useTranslations("allergens");
  const locale = useLocale();
  const [picked, setPicked] = useState<string[]>(initial.modifierIds);
  const [note, setNote] = useState(initial.note);
  const [quantity, setQuantity] = useState(initial.quantity);

  const chosen = options.filter((o) => picked.includes(o.id));
  const allergens = [...new Set([...item.allergens, ...chosen.flatMap((o) => o.allergens)])];
  const conflicts = allergens.filter((a) => tableAllergies.includes(a));
  const unit = (item.priceCents ?? 0) + chosen.reduce((sum, o) => sum + o.priceCents, 0);

  const toggle = (id: string) => setPicked((p) => (p.includes(id) ? p.filter((x) => x !== id) : [...p, id]));

  return (
    <Dialog open onClose={onClose} title={t("customiseTitle", { name: item.name })}>
      {conflicts.length > 0 ? (
        <p role="alert" className="rounded-lg bg-danger-soft px-3 py-2 text-sm font-extrabold text-danger-ink">
          {t("containsAllergen", { list: conflicts.map((a) => ta(a)).join(", ") })}
        </p>
      ) : null}

      {options.length === 0 ? <p className="text-sm text-muted">{t("noOptions")}</p> : null}
      {groupBySection(options.filter((o) => o.available || picked.includes(o.id))).map((group) => (
        <fieldset key={group.section} className="flex flex-col gap-1">
          <legend className="mb-1 text-xs font-extrabold tracking-wide text-primary-strong uppercase">{group.section || t("options")}</legend>
          <div className="grid gap-1.5 sm:grid-cols-2">
            {group.items.map((o) => {
              const on = picked.includes(o.id);
              const clash = o.allergens.filter((a) => tableAllergies.includes(a));
              return (
                <label
                  key={o.id}
                  className={`flex min-h-12 cursor-pointer items-center gap-3 rounded-xl border-2 px-3 py-1.5 ${
                    on ? "border-primary bg-primary-soft" : clash.length > 0 ? "border-danger" : "border-line"
                  } ${o.available ? "" : "opacity-50"}`}
                >
                  <input type="checkbox" checked={on} onChange={() => toggle(o.id)} className="h-5 w-5 shrink-0 accent-primary" />
                  <span className="flex min-w-0 flex-1 flex-col text-sm font-bold">
                    {o.name}
                    {clash.length > 0 ? <span className="text-xs font-bold text-danger-ink">{clash.map((a) => ta(a)).join(", ")}</span> : null}
                  </span>
                  {o.priceCents > 0 ? <Badge tone="neutral">+{formatCents(o.priceCents, locale)}</Badge> : null}
                </label>
              );
            })}
          </div>
        </fieldset>
      ))}

      <Field label={t("lineNote")} hint={t("lineNoteHint")} value={note} onChange={(e) => setNote(e.target.value)} maxLength={200} />

      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-2" role="group" aria-label={t("quantityOf", { name: item.name })}>
          <button type="button" aria-label={t("fewer")} disabled={quantity <= 1} onClick={() => setQuantity((q) => Math.max(1, q - 1))} className="min-h-12 min-w-12 rounded-[10px] border border-line-strong bg-surface text-xl font-bold disabled:opacity-40">
            −
          </button>
          <span className="min-w-8 text-center text-lg font-extrabold tabular-nums" aria-live="polite">
            {quantity}
          </span>
          <button type="button" aria-label={t("more")} disabled={quantity >= MAX_QUANTITY} onClick={() => setQuantity((q) => Math.min(MAX_QUANTITY, q + 1))} className="min-h-12 min-w-12 rounded-[10px] bg-primary text-xl font-bold text-on-primary disabled:opacity-40">
            +
          </button>
        </div>
        {item.priceCents != null || chosen.length > 0 ? <span className="text-sm font-bold text-muted tabular-nums">{formatCents(unit * quantity, locale)}</span> : null}
      </div>

      <div className="flex flex-wrap justify-end gap-3">
        <Button variant="secondary" onClick={onClose}>
          {t("cancelLine")}
        </Button>
        <Button onClick={() => onSave({ modifierIds: picked, note, quantity })}>{editing ? t("saveLine") : t("addToOrder")}</Button>
      </div>
    </Dialog>
  );
}
