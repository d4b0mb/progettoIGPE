"use client";

import { useTranslations } from "next-intl";
import { useRouter } from "next/navigation";
import { useEffect } from "react";
import { useSession } from "@/api/session";
import { LanguageSwitch } from "@/components/LanguageSwitch";

/** Sign-in and sign-up share this frame: brand panel beside the form, stacked on a phone. */
export default function AuthLayout({ children }: { children: React.ReactNode }) {
  const t = useTranslations("auth");
  const tc = useTranslations("common");
  const { state } = useSession();
  const router = useRouter();

  useEffect(() => {
    if (state.status === "authenticated") router.replace("/");
  }, [state.status, router]);

  return (
    <div className="flex min-h-dvh flex-col lg:flex-row">
      <section className="flex flex-col justify-between gap-8 bg-sidebar px-6 py-8 text-white lg:w-1/2 lg:px-16 lg:py-14">
        <div className="flex items-center gap-3">
          <div className="flex h-9 w-9 items-center justify-center rounded-[10px] bg-accent text-lg font-extrabold text-sidebar">R</div>
          <span className="text-xl font-extrabold">{tc("appName")}</span>
        </div>
        <div className="hidden max-w-md flex-col gap-5 lg:flex">
          <h2 className="text-5xl leading-[1.1] font-extrabold tracking-tight">{t("heroTitle")}</h2>
          <p className="text-lg text-sidebar-text/80">{t("heroText")}</p>
          <ul className="flex flex-col gap-3 text-sidebar-text">
            {(["heroPoint1", "heroPoint2", "heroPoint3"] as const).map((key) => (
              <li key={key} className="flex items-center gap-3">
                <span className="h-2 w-2 rounded-full bg-accent" aria-hidden="true" />
                {t(key)}
              </li>
            ))}
          </ul>
        </div>
        <p className="hidden max-w-md text-sm text-sidebar-muted lg:block">{t("heroFooter")}</p>
      </section>
      <main className="flex flex-1 flex-col gap-6 px-4 py-6 sm:px-10 lg:px-16">
        <div className="flex justify-end">
          <LanguageSwitch />
        </div>
        <div className="flex flex-1 items-center justify-center">{children}</div>
      </main>
    </div>
  );
}
