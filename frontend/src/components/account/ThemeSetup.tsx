"use client";

import { useTranslations } from "next-intl";
import { useState } from "react";
import { useRestaurant, type Theme } from "@/api/restaurant";
import { SliceMark } from "@/components/Brand";

const LOOKS: { theme: Theme; swatches: string[] }[] = [
  { theme: "CLASSIC", swatches: ["#0f2a24", "#0f6b5c", "#34b39b"] },
  { theme: "DUODECIM", swatches: ["#0e2b1a", "#1d6b3f", "#e0261d"] },
];

/** The owner picks how the app looks for the whole restaurant. */
export function ThemeSetup() {
  const t = useTranslations("account");
  const { theme, setTheme } = useRestaurant();
  const [error, setError] = useState<string | null>(null);

  async function choose(next: Theme) {
    setError(null);
    try {
      await setTheme(next);
    } catch (e) {
      setError((e as Error).message);
    }
  }

  return (
    <section className="flex flex-col gap-3 rounded-2xl border border-line bg-surface p-5" aria-label={t("lookTitle")}>
      <div className="flex flex-col gap-1">
        <h2 className="text-lg font-extrabold">{t("lookTitle")}</h2>
        <p className="text-sm text-muted">{t("lookHelp")}</p>
      </div>
      <div className="grid gap-3 sm:grid-cols-2" role="group" aria-label={t("lookTitle")}>
        {LOOKS.map((look) => (
          <button
            key={look.theme}
            type="button"
            aria-pressed={theme === look.theme}
            onClick={() => void choose(look.theme)}
            className={`flex min-h-16 items-center gap-3 rounded-xl border-2 p-3 text-left ${theme === look.theme ? "border-primary bg-primary-soft" : "border-line-strong bg-surface"}`}
          >
            {look.theme === "DUODECIM" ? <SliceMark className="h-8 w-8" /> : null}
            <span className="flex flex-col">
              <span className="font-extrabold">{t(look.theme === "CLASSIC" ? "lookClassic" : "lookDuodecim")}</span>
              <span className="mt-1 flex gap-1" aria-hidden="true">
                {look.swatches.map((c) => (
                  <span key={c} className="h-3 w-6 rounded-full" style={{ background: c }} />
                ))}
              </span>
            </span>
          </button>
        ))}
      </div>
      {error ? (
        <p role="alert" className="rounded-lg bg-danger-soft px-3 py-2 text-sm font-semibold text-danger-ink">
          {error}
        </p>
      ) : null}
    </section>
  );
}
