"use client";

import { useTranslations } from "next-intl";
import type { components } from "@/api/schema";

export type Allergen = components["schemas"]["ReservationRequest"]["allergens"] extends (infer A)[] | undefined ? A : never;

/** The 14 allergens EU food law requires restaurants to declare. */
export const ALLERGENS: Allergen[] = [
  "GLUTEN", "CRUSTACEANS", "EGGS", "FISH", "PEANUTS", "SOYBEANS", "MILK",
  "TREE_NUTS", "CELERY", "MUSTARD", "SESAME", "SULPHITES", "LUPIN", "MOLLUSCS",
];

/** Toggle chips for choosing allergens. Each chip is a real button with a pressed state. */
export function AllergenPicker({
  label,
  value,
  onChange,
}: {
  label: string;
  value: Allergen[];
  onChange: (next: Allergen[]) => void;
}) {
  const t = useTranslations("allergens");
  return (
    <fieldset className="flex flex-col gap-2">
      <legend className="text-sm font-bold">{label}</legend>
      <div className="flex flex-wrap gap-2">
        {ALLERGENS.map((allergen) => {
          const on = value.includes(allergen);
          return (
            <button
              key={allergen}
              type="button"
              aria-pressed={on}
              onClick={() => onChange(on ? value.filter((a) => a !== allergen) : [...value, allergen])}
              className={`min-h-11 rounded-full border px-3.5 text-sm font-semibold ${
                on ? "border-danger bg-danger-soft text-danger-ink" : "border-line-strong bg-surface text-muted"
              }`}
            >
              {t(allergen)}
            </button>
          );
        })}
      </div>
    </fieldset>
  );
}
