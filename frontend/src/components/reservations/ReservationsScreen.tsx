"use client";

import { useTranslations } from "next-intl";
import { useState } from "react";
import { useSession } from "@/api/session";
import { Button } from "@/components/ui/Button";
import { ReservationDay } from "./ReservationDay";
import { shiftDay, todayLocal } from "./time";

export function ReservationsScreen() {
  const t = useTranslations("reservations");
  const { state } = useSession();
  const [date, setDate] = useState(() => todayLocal());
  const [creating, setCreating] = useState(false);
  if (state.status !== "authenticated") return null;
  const canEdit = state.user.role === "OWNER" || state.user.role === "MANAGER";

  return (
    <div className="flex max-w-5xl flex-col gap-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-2xl font-extrabold tracking-tight">{t("title")}</h1>
        {canEdit ? <Button onClick={() => setCreating(true)}>{t("new")}</Button> : null}
      </div>

      <div className="flex flex-wrap items-center gap-2">
        <Button variant="secondary" aria-label={t("previousDay")} onClick={() => setDate((d) => shiftDay(d, -1))}>
          &lt;
        </Button>
        <label className="sr-only" htmlFor="day">
          {t("date")}
        </label>
        <input
          id="day"
          type="date"
          value={date}
          onChange={(e) => e.target.value && setDate(e.target.value)}
          className="min-h-11 rounded-[10px] border border-line-strong bg-surface px-3 text-base font-bold"
        />
        <Button variant="secondary" aria-label={t("nextDay")} onClick={() => setDate((d) => shiftDay(d, 1))}>
          &gt;
        </Button>
        <Button variant="secondary" onClick={() => setDate(todayLocal())}>
          {t("today")}
        </Button>
      </div>

      <ReservationDay key={date} date={date} canEdit={canEdit} creating={creating} onCloseCreate={() => setCreating(false)} />
    </div>
  );
}
