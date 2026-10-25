"use client";

import { useTranslations } from "next-intl";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect } from "react";
import { RestaurantProvider } from "@/api/restaurant";
import { useSession } from "@/api/session";
import { Shell } from "@/components/Shell";
import { mayOpen } from "@/components/nav";

/** Everything inside (app) needs a signed-in user; anyone else is sent to the sign-in page. */
export default function AppLayout({ children }: { children: React.ReactNode }) {
  const { state } = useSession();
  const router = useRouter();
  const pathname = usePathname();
  const t = useTranslations("common");

  useEffect(() => {
    if (state.status === "anonymous") router.replace("/login");
  }, [state.status, router]);

  if (state.status !== "authenticated") {
    return (
      <div className="flex min-h-dvh items-center justify-center text-muted" role="status">
        {t("loading")}
      </div>
    );
  }
  const allowed = mayOpen(state.user.role, pathname);
  return (
    <RestaurantProvider>
    <Shell user={state.user}>
      {allowed ? (
        children
      ) : (
        <div role="alert" className="flex max-w-md flex-col gap-3">
          <p className="text-lg font-bold">{t("noAccess")}</p>
          <Link href="/" className="font-bold text-primary underline">
            {t("goHome")}
          </Link>
        </div>
      )}
    </Shell>
    </RestaurantProvider>
  );
}
