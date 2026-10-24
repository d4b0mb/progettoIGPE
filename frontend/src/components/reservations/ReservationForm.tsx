"use client";

import { useTranslations } from "next-intl";
import { useCallback, useState, type FormEvent } from "react";
import { api } from "@/api/client";
import { unwrap } from "@/api/call";
import { ApiError, toApiError } from "@/api/errors";
import type { components } from "@/api/schema";
import { AllergenPicker, type Allergen } from "@/components/AllergenPicker";
import { Button } from "@/components/ui/Button";
import { SelectField, TextareaField } from "@/components/ui/Controls";
import { Field } from "@/components/ui/Field";
import { useRemote } from "@/lib/useRemote";
import { dateOf, timeOf, toInstant } from "./time";

type Reservation = components["schemas"]["Reservation"];
type Draft = components["schemas"]["Draft"];

const num = (value: string, fallback = 0) => (value === "" || Number.isNaN(Number(value)) ? fallback : Number(value));

/**
 * Create or edit a reservation. As the date, time, duration or party size change,
 * the list of free tables is refreshed (smallest table that fits first) so staff
 * only ever pick a table that will be accepted.
 */
export function ReservationForm({
  existing,
  defaultDate,
  onSaved,
  onCancel,
}: {
  existing?: Reservation;
  defaultDate: string;
  onSaved: () => void;
  onCancel: () => void;
}) {
  const t = useTranslations("reservations");
  const tc = useTranslations("common");
  const ts = useTranslations("reservationStatus");

  const [guestName, setGuestName] = useState(existing?.guestName ?? "");
  const [phone, setPhone] = useState(existing?.phone ?? "");
  const [email, setEmail] = useState(existing?.email ?? "");
  const [date, setDate] = useState(existing ? dateOf(existing.startsAt) : defaultDate);
  const [time, setTime] = useState(existing ? timeOf(existing.startsAt) : "20:00");
  const [duration, setDuration] = useState(String(existing?.durationMinutes ?? 120));
  const [partySize, setPartySize] = useState(String(existing?.partySize ?? 2));
  const [highChairs, setHighChairs] = useState(String(existing?.highChairs ?? 0));
  const [strollers, setStrollers] = useState(String(existing?.strollerSpaces ?? 0));
  const [allergens, setAllergens] = useState<Allergen[]>(existing?.allergens ?? []);
  const [notes, setNotes] = useState(existing?.notes ?? "");
  const [tableId, setTableId] = useState(existing?.tableId ?? "");
  const [status, setStatus] = useState<NonNullable<Draft["status"]>>(existing?.status ?? "CONFIRMED");
  const [problem, setProblem] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(false);

  const when = date && time ? toInstant(date, time) : null;
  const guests = num(partySize);
  const minutes = num(duration, 120);

  const loadTables = useCallback(async () => {
    if (!when || guests < 1 || guests > 40) return [];
    return unwrap(
      api.GET("/api/v1/reservations/available-tables", {
        params: { query: { startsAt: when, durationMinutes: minutes, partySize: guests, excludeReservationId: existing?.id } },
      }),
    );
  }, [when, guests, minutes, existing?.id]);
  const tables = useRemote(loadTables);

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    if (!when) return;
    setBusy(true);
    setProblem(null);
    const body: Draft = {
      guestName,
      phone,
      email: email.trim() || undefined,
      startsAt: when,
      durationMinutes: minutes,
      partySize: guests,
      highChairs: num(highChairs),
      strollerSpaces: num(strollers),
      tableId: tableId || undefined,
      status,
      allergens,
      notes,
    };
    try {
      if (existing) {
        await unwrap(api.PUT("/api/v1/reservations/{id}", { params: { path: { id: existing.id } }, body }));
      } else {
        await unwrap(api.POST("/api/v1/reservations", { body }));
      }
      onSaved();
    } catch (error) {
      setProblem(error instanceof ApiError ? error : toApiError(500, undefined));
      setBusy(false);
    }
  }

  const fe = problem?.fields ?? {};
  // A table error that is not about one field (a booking clash) is shown above the buttons.
  const general = problem && Object.keys(fe).length === 0 ? problem.message : null;
  const options = tables.data ?? [];
  const keepsCurrent = tableId !== "" && !options.some((o) => o.id === tableId);

  return (
    <form onSubmit={onSubmit} className="flex flex-col gap-4" noValidate>
      <Field label={t("guestName")} value={guestName} onChange={(e) => setGuestName(e.target.value)} required maxLength={120} error={fe.guestName} />
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label={t("phone")} type="tel" inputMode="tel" autoComplete="off" value={phone} onChange={(e) => setPhone(e.target.value)} required error={fe.phone} />
        <Field label={t("email")} type="email" value={email} onChange={(e) => setEmail(e.target.value)} error={fe.email} />
      </div>
      <div className="grid grid-cols-2 gap-4 sm:grid-cols-3">
        <Field label={t("date")} type="date" value={date} onChange={(e) => setDate(e.target.value)} required error={fe.startsAt} />
        <Field label={t("time")} type="time" value={time} onChange={(e) => setTime(e.target.value)} required />
        <Field label={t("duration")} type="number" inputMode="numeric" min={15} max={720} step={15} value={duration} onChange={(e) => setDuration(e.target.value)} error={fe.durationMinutes} />
      </div>
      <div className="grid grid-cols-3 gap-4">
        <Field label={t("partySize")} type="number" inputMode="numeric" min={1} max={40} value={partySize} onChange={(e) => setPartySize(e.target.value)} required error={fe.partySize} />
        <Field label={t("highChairs")} type="number" inputMode="numeric" min={0} value={highChairs} onChange={(e) => setHighChairs(e.target.value)} error={fe.highChairs} />
        <Field label={t("strollers")} type="number" inputMode="numeric" min={0} max={6} value={strollers} onChange={(e) => setStrollers(e.target.value)} error={fe.strollerSpaces} />
      </div>
      <SelectField
        label={t("table")}
        value={tableId}
        onChange={(e) => setTableId(e.target.value)}
        error={fe.tableId}
        hint={!tables.loading && options.length === 0 && guests >= 1 ? t("noTables") : undefined}
      >
        <option value="">{t("unassigned")}</option>
        {keepsCurrent ? <option value={tableId}>{`#${tableId.slice(0, 4)}`}</option> : null}
        {options.map((table) => (
          <option key={table.id} value={table.id}>
            {t("tableSeats", { number: table.number, seats: table.seats })}
          </option>
        ))}
      </SelectField>
      <AllergenPicker label={t("allergies")} value={allergens} onChange={setAllergens} />
      <TextareaField label={t("notes")} value={notes} onChange={(e) => setNotes(e.target.value)} maxLength={1000} error={fe.notes} />
      {existing ? (
        <SelectField label={t("status")} value={status} onChange={(e) => setStatus(e.target.value as typeof status)}>
          {(["PENDING", "CONFIRMED", "ARRIVED", "COMPLETED", "CANCELLED", "NO_SHOW"] as const).map((s) => (
            <option key={s} value={s}>
              {ts(s)}
            </option>
          ))}
        </SelectField>
      ) : null}
      {general ? (
        <p role="alert" className="rounded-lg bg-danger-soft px-3 py-2 text-sm font-semibold text-danger-ink">
          {general}
        </p>
      ) : null}
      <div className="flex flex-wrap justify-end gap-3">
        <Button variant="secondary" onClick={onCancel}>
          {tc("cancel")}
        </Button>
        <Button type="submit" disabled={busy}>
          {tc("save")}
        </Button>
      </div>
    </form>
  );
}
