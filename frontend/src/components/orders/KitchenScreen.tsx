"use client";

import { useTranslations } from "next-intl";
import { useState } from "react";
import { useSession } from "@/api/session";
import { Button } from "@/components/ui/Button";
import { useWakeLock } from "@/lib/useWakeLock";
import { TicketCard } from "./TicketCard";
import type { Ticket, TicketStatus } from "./tickets";
import { useNow, useTicketStream } from "./useTicketStream";

/** The kitchen's wall screen: live orders in three columns, the next action one big tap away. */
export function KitchenScreen() {
  const t = useTranslations("kitchen");
  const { state } = useSession();
  const { tickets, connected, loaded, setStatus } = useTicketStream();
  const now = useNow();
  useWakeLock();
  const [error, setError] = useState<string | null>(null);

  if (state.status !== "authenticated") return null;
  const role = state.user.role;
  const canCook = role === "KITCHEN" || role === "OWNER" || role === "MANAGER";

  async function move(ticket: Ticket, status: TicketStatus) {
    setError(null);
    try {
      await setStatus(ticket, status);
    } catch (e) {
      setError((e as Error).message);
    }
  }

  const columns: { key: "colNew" | "colPreparing" | "colReady"; status: TicketStatus }[] = [
    { key: "colNew", status: "NEW" },
    { key: "colPreparing", status: "PREPARING" },
    { key: "colReady", status: "READY" },
  ];

  const bigButton = "min-h-14 flex-1 text-base";
  return (
    <div className="-m-4 flex min-h-[calc(100dvh-4.5rem)] flex-col gap-4 bg-[#0b1512] p-4 text-white sm:-m-6 sm:p-6 md:-m-8 md:min-h-dvh md:p-8">
      <header className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-4">
          <h1 className="text-3xl font-extrabold">{t("title")}</h1>
          <span
            role="status"
            className={`flex items-center gap-2 rounded-full px-3 py-1.5 text-sm font-bold ${connected ? "bg-[#12362e] text-[#8fe3cf]" : "bg-amber-900 text-amber-100"}`}
          >
            <span className={`h-2 w-2 rounded-full ${connected ? "bg-[#34d3b0]" : "bg-amber-300"}`} aria-hidden="true" />
            {connected ? t("live") : t("reconnecting")}
          </span>
        </div>
        <span className="text-xl font-extrabold text-white/70 tabular-nums">{new Date(now).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}</span>
      </header>

      {error ? (
        <p role="alert" className="rounded-lg bg-red-100 px-3 py-2 text-sm font-semibold text-red-900">
          {error}
        </p>
      ) : null}
      {loaded && tickets.length === 0 ? <p className="rounded-2xl border border-dashed border-white/25 p-8 text-center text-white/70">{t("allClear")}</p> : null}

      <div className="grid gap-5 md:grid-cols-3 md:items-start">
        {columns.map((column) => {
          const inColumn = tickets.filter((x) => x.status === column.status);
          return (
            <section key={column.status} className="flex flex-col gap-3" aria-label={t(column.key)}>
              <h2 className="text-sm font-bold tracking-widest text-white/60 uppercase">
                {t(column.key)} · {inColumn.length}
              </h2>
              {inColumn.map((ticket) => (
                <TicketCard
                  key={ticket.id}
                  ticket={ticket}
                  now={now}
                  actions={
                    canCook ? (
                      column.status === "NEW" ? (
                        <Button className={bigButton} onClick={() => void move(ticket, "PREPARING")}>
                          {t("start")}
                        </Button>
                      ) : column.status === "PREPARING" ? (
                        <Button className={`${bigButton} !bg-white !text-[#06241d]`} onClick={() => void move(ticket, "READY")}>
                          {t("markReady")}
                        </Button>
                      ) : (
                        <Button variant="secondary" className={`${bigButton} !border-white/30 !bg-transparent !text-white`} onClick={() => void move(ticket, "PREPARING")}>
                          {t("backToPreparing")}
                        </Button>
                      )
                    ) : null
                  }
                />
              ))}
            </section>
          );
        })}
      </div>
    </div>
  );
}
