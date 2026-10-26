"use client";

import { useTranslations } from "next-intl";
import type { ReactNode } from "react";
import { formatTimeTyping, parseTime } from "@/components/reservations/time";
import { Field } from "./Field";

/**
 * A time of day on the 24-hour clock ("20:30"). Deliberately not the browser's own time picker, which shows
 * AM and PM on many devices: here the colon appears by itself and only real times are accepted.
 */
export function TimeField({
  label,
  value,
  onChange,
  required,
  hint,
  error,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  required?: boolean;
  hint?: ReactNode;
  error?: string;
}) {
  const t = useTranslations("common");
  const invalid = value !== "" && parseTime(value) === null;
  return (
    <Field
      label={label}
      value={value}
      inputMode="numeric"
      autoComplete="off"
      placeholder="20:00"
      maxLength={5}
      required={required}
      hint={hint}
      error={error ?? (invalid && value.length >= 4 ? t("timeInvalid") : undefined)}
      onChange={(e) => onChange(formatTimeTyping(e.target.value))}
      onBlur={() => {
        const parsed = parseTime(value);
        if (parsed && parsed !== value) onChange(parsed);
      }}
    />
  );
}
