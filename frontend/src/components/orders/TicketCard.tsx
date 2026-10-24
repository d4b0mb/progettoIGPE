"use client";

import { useTranslations } from "next-intl";
import type { ReactNode } from "react";
import { elapsedText, isLate, minutesSince, type Ticket } from "./tickets";

const PRIORITY_STYLE = {
  NORMAL: null,
  HIGH: "bg-amber-400 text-amber-950",
  URGENT: "bg-red-500 text-white",
  VIP: "bg-fuchsia-500 text-white",
} as const;

/** One order as the kitchen reads it: what matters most (allergies, priority, lateness) is hardest to miss. */
export function TicketCard({ ticket, now, actions }: { ticket: Ticket; now: number; actions: ReactNode }) {
  const t = useTranslations("kitchen");
  const tp = useTranslations("priority");
  const ta = useTranslations("allergens");
  const tc = useTranslations("courses");

  const late = isLate(ticket, now);
  const priorityStyle = PRIORITY_STYLE[ticket.priority];
  const conflictAllergens = [...new Set(ticket.items.flatMap((i) => i.conflicts))];
  const conflictDishes = ticket.items.filter((i) => i.conflicts.length > 0).map((i) => i.name);
  const border = ticket.hasAllergyConflicts ? "border-red-500" : ticket.priority === "VIP" ? "border-fuchsia-500" : ticket.priority === "URGENT" ? "border-red-500" : "border-white/15";

  return (
    <article className={`flex flex-col gap-3 rounded-2xl border-[3px] bg-[#16231f] p-4 text-white ${border}`} aria-label={`${ticket.code}`}>
      <header className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-baseline gap-3">
          <span className="text-2xl font-extrabold">{ticket.tableNumber != null ? t("tableLabel", { number: ticket.tableNumber }) : ticket.code}</span>
          <span className="text-sm font-bold text-white/60">{ticket.code}</span>
        </div>
        <div className="flex items-center gap-2">
          {priorityStyle ? <span className={`rounded-md px-2 py-1 text-xs font-extrabold ${priorityStyle}`}>{tp(ticket.priority).toUpperCase()}</span> : null}
          <span className={`text-sm font-bold tabular-nums ${late ? "text-red-300" : "text-white/70"}`}>
            {late ? `${t("late")} · ` : ""}
            {elapsedText(minutesSince(ticket.createdAt, now))}
          </span>
        </div>
      </header>

      {ticket.hasAllergyConflicts ? (
        <p role="alert" className="rounded-lg bg-red-100 px-3 py-2 text-base font-extrabold text-red-900">
          {t("allergyConflict", { list: conflictAllergens.map((a) => ta(a)).join(", ") })}
          <span className="block text-sm font-bold">{t("conflictsIn", { dishes: conflictDishes.join(", ") })}</span>
        </p>
      ) : null}
      {ticket.guestAllergens.length > 0 && !ticket.hasAllergyConflicts ? (
        <p className="rounded-lg bg-amber-100 px-3 py-2 text-sm font-bold text-amber-950">
          {t("guestAllergies", { list: ticket.guestAllergens.map((a) => ta(a)).join(", ") })}
        </p>
      ) : null}

      <ul className="flex flex-col gap-1.5">
        {ticket.items.map((item) => (
          <li key={item.id} className={`flex flex-col rounded-md px-2 py-1 text-[17px] font-semibold ${item.conflicts.length > 0 ? "bg-red-500/25" : ""}`}>
            <span>
              <span className="tabular-nums">{item.quantity} ×</span> {item.name}
              <span className="ml-2 text-xs font-bold text-white/50">{tc(item.course)}</span>
            </span>
            {item.allergens.length > 0 ? <span className="text-xs text-white/60">{item.allergens.map((a) => ta(a)).join(" · ")}</span> : null}
            {item.note ? <span className="text-sm text-amber-200">{item.note}</span> : null}
          </li>
        ))}
      </ul>

      {ticket.note ? (
        <p className="text-sm text-amber-200">
          <span className="font-bold">{t("note")}:</span> {ticket.note}
        </p>
      ) : null}
      <div className="flex flex-wrap gap-2">{actions}</div>
    </article>
  );
}
