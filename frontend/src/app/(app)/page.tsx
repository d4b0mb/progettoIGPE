"use client";

import { useTranslations } from "next-intl";
import { useRouter } from "next/navigation";
import { useEffect } from "react";
import { useSession } from "@/api/session";

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
    <div className="flex max-w-2xl flex-col gap-2">
      <h1 className="text-2xl font-extrabold tracking-tight">{t("welcome", { name: state.user.displayName })}</h1>
      <p className="text-muted">{t("intro")}</p>
    </div>
  );
}
