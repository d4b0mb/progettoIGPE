"use client";

import { useTranslations } from "next-intl";
import { useState, type FormEvent } from "react";
import { api } from "@/api/client";
import { unwrap } from "@/api/call";
import { ApiError } from "@/api/errors";
import type { components } from "@/api/schema";
import { AllergenPicker, type Allergen } from "@/components/AllergenPicker";
import { Button } from "@/components/ui/Button";
import { SelectField } from "@/components/ui/Controls";
import { Field } from "@/components/ui/Field";
import { COURSES } from "./MenuForm";
import { centsToInput, parsePriceToCents } from "./money";

type Modifier = components["schemas"]["Modifier"];
type Course = Modifier["course"];

/** Add or change an option (a topping, a removal, a variant) for the dishes of one course. */
export function ModifierForm({ existing, onSaved, onCancel }: { existing?: Modifier; onSaved: () => void; onCancel: () => void }) {
  const t = useTranslations("menu");
  const tc = useTranslations("common");
  const tcourse = useTranslations("courses");
  const [name, setName] = useState(existing?.name ?? "");
  const [course, setCourse] = useState<Course>(existing?.course ?? "PIZZA");
  const [section, setSection] = useState(existing?.section ?? "");
  const [price, setPrice] = useState(existing ? centsToInput(existing.priceCents) : "");
  const [allergens, setAllergens] = useState<Allergen[]>(existing?.allergens ?? []);
  const [available, setAvailable] = useState(existing?.available ?? true);
  const [problem, setProblem] = useState<ApiError | null>(null);
  const [priceError, setPriceError] = useState<string | undefined>();
  const [busy, setBusy] = useState(false);

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    const cents = parsePriceToCents(price);
    if (cents === null) {
      setPriceError(t("price"));
      return;
    }
    setPriceError(undefined);
    setBusy(true);
    setProblem(null);
    // An empty price means the option is free.
    const body = { name, course, section: section.trim(), priceCents: cents ?? 0, allergens, available };
    try {
      if (existing) await unwrap(api.PUT("/api/v1/menu/modifiers/{id}", { params: { path: { id: existing.id } }, body }));
      else await unwrap(api.POST("/api/v1/menu/modifiers", { body }));
      onSaved();
    } catch (error) {
      setProblem(error as ApiError);
      setBusy(false);
    }
  }

  const fe = problem?.fields ?? {};
  const general = problem && Object.keys(fe).length === 0 ? problem.message : null;
  return (
    <form onSubmit={onSubmit} className="flex flex-col gap-4" noValidate>
      <Field label={t("optionName")} value={name} onChange={(e) => setName(e.target.value)} required maxLength={120} error={fe.name} />
      <div className="grid gap-4 sm:grid-cols-2">
        <SelectField label={t("optionCourse")} value={course} onChange={(e) => setCourse(e.target.value as Course)} hint={t("optionCourseHint")}>
          {COURSES.map((c) => (
            <option key={c} value={c}>
              {tcourse(c)}
            </option>
          ))}
        </SelectField>
        <Field label={t("optionPrice")} inputMode="decimal" value={price} onChange={(e) => setPrice(e.target.value)} error={priceError ?? fe.priceCents} />
      </div>
      <Field label={t("section")} hint={t("optionSectionHint")} value={section} onChange={(e) => setSection(e.target.value)} maxLength={60} error={fe.section} />
      <AllergenPicker label={t("optionAllergens")} value={allergens} onChange={setAllergens} />
      <label className="flex min-h-11 items-center gap-3 text-sm font-bold">
        <input type="checkbox" checked={available} onChange={(e) => setAvailable(e.target.checked)} className="h-5 w-5 accent-primary" />
        {t("available")}
      </label>
      {general ? (
        <p role="alert" className="rounded-lg bg-danger-soft px-3 py-2 text-sm font-semibold text-danger-ink">
          {general}
        </p>
      ) : null}
      <div className="flex flex-wrap justify-end gap-3">
        <Button variant="secondary" onClick={onCancel}>
          {tc("cancel")}
        </Button>
        <Button type="submit" disabled={busy}>
          {tc("save")}
        </Button>
      </div>
    </form>
  );
}
