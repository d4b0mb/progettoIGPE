"use client";

import { useLocale, useTranslations } from "next-intl";
import { useRouter } from "next/navigation";
import { useTransition } from "react";
import { setLocale } from "@/i18n/actions";
import type { Locale } from "@/i18n/request";

export function LanguageSwitch({ tone = "light" }: { tone?: "light" | "dark" }) {
  const current = useLocale();
  const t = useTranslations("common");
  const router = useRouter();
  const [pending, start] = useTransition();

  function choose(locale: Locale) {
    if (locale === current) return;
    start(async () => {
      await setLocale(locale);
      router.refresh();
    });
  }

  const base = "min-h-11 min-w-11 border px-3 text-sm font-bold";
  const on = tone === "dark" ? "border-accent bg-sidebar-active text-white" : "border-primary bg-primary-soft text-primary-strong";
  const off = tone === "dark" ? "border-sidebar-active text-sidebar-muted" : "border-line bg-surface text-muted";
  return (
    <div role="group" aria-label={t("language")} className="flex" aria-busy={pending}>
      {(["it", "en"] as const).map((locale, i) => (
        <button
          key={locale}
          type="button"
          lang={locale}
          aria-pressed={current === locale}
          onClick={() => choose(locale)}
          className={`${base} ${i === 0 ? "rounded-l-lg" : "rounded-r-lg border-l-0"} ${current === locale ? on : off}`}
        >
          {locale.toUpperCase()}
        </button>
      ))}
    </div>
  );
}
