"use client";

import { useTranslations } from "next-intl";
import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { OrderEntry } from "./OrderEntry";
import { servingPosition } from "./tickets";
import { TicketCard } from "./TicketCard";
import { useNow, useTicketStream } from "./useTicketStream";

/** The waiter's screen: what is ready to carry out, and a new order underneath. */
export function OrdersScreen() {
  const t = useTranslations("orders");
  const { tickets, setStatus } = useTicketStream();
  const now = useNow();
  const [sent, setSent] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const ready = tickets.filter((x) => x.status === "READY");

  return (
    <div className="flex max-w-4xl flex-col gap-6">
      <h1 className="text-2xl font-extrabold tracking-tight">{t("title")}</h1>

      {sent ? (
        <p role="status" className="rounded-lg bg-primary-soft px-3 py-2 text-sm font-bold text-primary-strong">
          {t("sent", { code: sent })}
        </p>
      ) : null}
      {error ? (
        <p role="alert" className="rounded-lg bg-danger-soft px-3 py-2 text-sm font-semibold text-danger-ink">
          {error}
        </p>
      ) : null}

      <section className="flex flex-col gap-3" aria-label={t("readyToServe")}>
        <h2 className="text-sm font-extrabold tracking-wide text-muted uppercase">
          {t("readyToServe")} · {ready.length}
        </h2>
        {ready.length === 0 ? <p className="text-sm text-muted">{t("nothingReady")}</p> : null}
        <div className="grid gap-3 sm:grid-cols-2">
          {ready.map((ticket) => (
            <TicketCard
              key={ticket.id}
              ticket={ticket}
              now={now}
              actions={
                <Button
                  className="min-h-14 flex-1 !bg-white !text-[#06241d]"
                  onClick={() => void setStatus(ticket, "SERVED").catch((e: Error) => setError(e.message))}
                >
                  {servingPosition(ticket).count > 1 ? t("servedServing", { index: servingPosition(ticket).index, count: servingPosition(ticket).count }) : t("served")}
                </Button>
              }
            />
          ))}
        </div>
      </section>

      <section className="flex flex-col gap-3" aria-label={t("newOrder")}>
        <h2 className="text-lg font-extrabold">{t("newOrder")}</h2>
        <OrderEntry onSent={setSent} />
      </section>
    </div>
  );
}
