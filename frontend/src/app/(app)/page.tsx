"use client";

import { useTranslations } from "next-intl";
import { useRouter } from "next/navigation";
import { useEffect } from "react";
import Link from "next/link";
import { api } from "@/api/client";
import { unwrap } from "@/api/call";
import { useSession } from "@/api/session";
import { useRemote } from "@/lib/useRemote";
import { useCallback } from "react";

/** Shown to owners who have not set up account recovery: without it a forgotten password cannot be recovered. */
function RecoveryBanner() {
  const t = useTranslations("account");
  const load = useCallback(() => unwrap(api.GET("/api/v1/me/recovery")), []);
  const status = useRemote(load);
  if (!status.data || status.data.configured) return null;
  return (
    <div role="note" className="flex max-w-2xl flex-col gap-2 rounded-2xl border border-amber-500 bg-warn-soft p-4 text-warn-ink">
      <p className="font-extrabold">{t("bannerTitle")}</p>
      <p className="text-sm">{t("bannerText")}</p>
      <Link href="/account" className="min-h-11 self-start rounded-[10px] bg-primary px-4 py-3 text-sm font-bold text-white">
        {t("bannerAction")}
      </Link>
    </div>
  );
}

export default function HomePage() {
  const t = useTranslations("home");
  const { state } = useSession();
  const router = useRouter();
  const isKitchen = state.status === "authenticated" && state.user.role === "KITCHEN";
  // Kitchen staff have one screen, so they land on it.
  useEffect(() => {
    if (isKitchen) router.replace("/kitchen");
  }, [isKitchen, router]);
  if (state.status !== "authenticated") return null;
  return (
    <div className="flex max-w-2xl flex-col gap-4">
      {state.user.role === "OWNER" ? <RecoveryBanner /> : null}
      <h1 className="text-2xl font-extrabold tracking-tight">{t("welcome", { name: state.user.displayName })}</h1>
      <p className="text-muted">{t("intro")}</p>
    </div>
  );
}
