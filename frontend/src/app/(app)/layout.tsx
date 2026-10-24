"use client";

import { useTranslations } from "next-intl";
import { useRouter } from "next/navigation";
import { useEffect } from "react";
import { useSession } from "@/api/session";
import { Shell } from "@/components/Shell";

/** Everything inside (app) needs a signed-in user; anyone else is sent to the sign-in page. */
export default function AppLayout({ children }: { children: React.ReactNode }) {
  const { state } = useSession();
  const router = useRouter();
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
  return <Shell user={state.user}>{children}</Shell>;
}
