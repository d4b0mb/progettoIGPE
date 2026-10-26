"use client";

import { useTranslations } from "next-intl";
import { useMode } from "@/lib/mode";
import { Icon } from "@/components/ui/Icon";

/** The sun/moon button: switches between the light and the dark look. */
export function ModeToggle({ tone = "light" }: { tone?: "light" | "dark" }) {
  const t = useTranslations("common");
  const [mode, toggle] = useMode();
  const dark = mode === "dark";
  return (
    <button
      type="button"
      aria-pressed={dark}
      onClick={toggle}
      className={`flex min-h-11 min-w-11 items-center justify-center rounded-[10px] border ${
        tone === "dark" ? "border-white/20 text-sidebar-text hover:bg-sidebar-active/60" : "border-line-strong bg-surface text-ink hover:bg-canvas"
      }`}
    >
      <Icon name={dark ? "sun" : "moon"} />
      <span className="sr-only">{dark ? t("lightMode") : t("darkMode")}</span>
    </button>
  );
}
