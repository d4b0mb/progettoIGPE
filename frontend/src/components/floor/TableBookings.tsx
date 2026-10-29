"use client";

import Link from "next/link";
import { useTranslations } from "next-intl";
import type { components } from "@/api/schema";
import { endTime, timeOf } from "@/components/reservations/time";
import { Badge } from "@/components/ui/Badge";
import { Button } from "@/components/ui/Button";
import { Dialog } from "@/components/ui/Dialog";

type Reservation = components["schemas"]["Reservation"];

// Same colours as the reservation list, so a status looks the same everywhere.
const TONE: Record<Reservation["status"], "neutral" | "good" | "warn" | "danger" | "info"> = {
  PENDING: "neutral",
  CONFIRMED: "good",
  ARRIVED: "info",
  COMPLETED: "neutral",
  CANCELLED: "danger",
  NO_SHOW: "warn",
};

/** Who has the table today: one card per booking, the party seated now first (see bookingsForTable). */
export function TableBookings({ open, onClose, tableNumber, bookings }: { open: boolean; onClose: () => void; tableNumber: number; bookings: Reservation[] }) {
  const t = useTranslations("floor");
  const tr = useTranslations("reservations");
  const ts = useTranslations("reservationStatus");
  const ta = useTranslations("allergens");
  // With several bookings, point out the one that comes up next. Alone, a single card needs no label.
  const nextIndex = bookings.length > 1 ? bookings.findIndex((r) => r.status !== "ARRIVED") : -1;

  return (
    <Dialog open={open} onClose={onClose} title={t("bookingsTitle", { number: tableNumber })}>
      {bookings.length === 0 ? <p className="text-sm text-muted">{t("noBookings")}</p> : null}
      {bookings.length > 1 ? <p className="text-sm font-bold text-muted">{t("bookingsCount", { count: bookings.length })}</p> : null}

      <ul className="flex flex-col gap-3">
        {bookings.map((r, i) => (
          <li key={r.id} className="flex flex-col gap-2 rounded-2xl border border-line bg-surface p-4">
            <div className="flex flex-wrap items-center gap-2">
              <span className="text-xl font-extrabold tabular-nums">
                {timeOf(r.startsAt)} – {endTime(r.startsAt, r.durationMinutes)}
              </span>
              <Badge tone={TONE[r.status]}>{ts(r.status)}</Badge>
              {r.status === "ARRIVED" ? <span className="text-xs font-bold tracking-wide text-info-ink uppercase">{t("seatedNow")}</span> : null}
              {i === nextIndex ? <span className="text-xs font-bold tracking-wide text-muted uppercase">{t("next")}</span> : null}
            </div>

            <p className="text-base">
              <span className="font-bold">{r.guestName}</span> · <span className="text-muted">{tr("guests", { count: r.partySize })}</span>
            </p>

            {r.phone ? (
              <a href={`tel:${r.phone.replace(/[^\d+]/g, "")}`} className="inline-flex min-h-11 items-center self-start font-semibold text-primary-strong underline">
                {r.phone}
              </a>
            ) : null}
            {r.email ? <p className="text-sm break-all text-muted">{r.email}</p> : null}

            {r.allergens.length > 0 || r.highChairs > 0 || r.strollerSpaces > 0 ? (
              <div className="flex flex-wrap items-center gap-2">
                {r.allergens.map((a) => (
                  <Badge key={a} tone="danger">
                    {ta(a)}
                  </Badge>
                ))}
                {r.highChairs > 0 ? <Badge tone="warn">{tr("highChairsCount", { count: r.highChairs })}</Badge> : null}
                {r.strollerSpaces > 0 ? <Badge tone="warn">{tr("strollersCount", { count: r.strollerSpaces })}</Badge> : null}
              </div>
            ) : null}
            {r.notes.trim() ? <p className="text-sm text-muted">{r.notes}</p> : null}
          </li>
        ))}
      </ul>

      <div className="flex flex-wrap items-center justify-between gap-3">
        <Link href="/reservations" className="inline-flex min-h-11 items-center font-bold text-primary-strong underline">
          {t("openReservations")}
        </Link>
        <Button variant="secondary" onClick={onClose}>
          {t("close")}
        </Button>
      </div>
    </Dialog>
  );
}
