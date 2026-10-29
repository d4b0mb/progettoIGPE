"use client";

import { useTranslations } from "next-intl";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect } from "react";
import { RestaurantProvider } from "@/api/restaurant";
import { useSession } from "@/api/session";
import { AssistantButton } from "@/components/assistant/AssistantButton";
import { AssistantPanel } from "@/components/assistant/AssistantPanel";
import { AssistantProvider } from "@/components/assistant/AssistantProvider";
import { Shell } from "@/components/Shell";
import { mayOpen } from "@/components/nav";

/** Everything inside (app) needs a signed-in user; anyone else is sent to the sign-in page. */
export default function AppLayout({ children }: { children: React.ReactNode }) {
  const { state } = useSession();
  const router = useRouter();
  const pathname = usePathname();
  const t = useTranslations("common");

  // A session the server ended gets a fixed reason code, so the sign-in page can explain why.
  const expired = state.status === "anonymous" && state.reason === "expired";
  useEffect(() => {
    if (state.status === "anonymous") router.replace(expired ? "/login?reason=expired" : "/login");
  }, [state.status, expired, router]);

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
    {/* Keyed by the person: someone else signing in (or nobody) never inherits the conversation. */}
    <AssistantProvider key={state.user.id} role={state.user.role}>
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
    <AssistantButton />
    <AssistantPanel />
    </AssistantProvider>
    </RestaurantProvider>
  );
}
