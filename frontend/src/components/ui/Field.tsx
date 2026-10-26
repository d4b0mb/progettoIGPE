"use client";

import { useTranslations } from "next-intl";
import { useId, useState, type InputHTMLAttributes, type ReactNode } from "react";
import { Icon } from "./Icon";

interface FieldProps extends InputHTMLAttributes<HTMLInputElement> {
  label: string;
  error?: string;
  hint?: ReactNode;
}

/**
 * A labelled input. The label, hint and error are all tied to the input for screen readers.
 * A password input gets an eye button that shows or hides what was typed, which helps on a phone keyboard.
 */
export function Field({ label, error, hint, className = "", type, ...props }: FieldProps) {
  const id = useId();
  const hintId = `${id}-hint`;
  const errorId = `${id}-error`;
  const t = useTranslations("common");
  const [revealed, setRevealed] = useState(false);
  const isPassword = type === "password";
  const described = [hint ? hintId : null, error ? errorId : null].filter(Boolean).join(" ") || undefined;
  return (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={id} className="text-sm font-bold">
        {label}
      </label>
      <div className="relative flex">
        <input
          id={id}
          type={isPassword && revealed ? "text" : type}
          aria-invalid={error ? true : undefined}
          aria-describedby={described}
          className={`min-h-12 w-full rounded-[10px] border bg-surface px-3.5 text-base ${isPassword ? "pr-12" : ""} ${
            error ? "border-danger" : "border-line-strong"
          } ${className}`}
          {...props}
        />
        {isPassword ? (
          <button
            type="button"
            aria-pressed={revealed}
            aria-controls={id}
            onClick={() => setRevealed((r) => !r)}
            className="absolute inset-y-0 right-0 flex min-w-12 items-center justify-center rounded-r-[10px] text-muted hover:text-ink"
          >
            <Icon name={revealed ? "eyeOff" : "eye"} />
            <span className="sr-only">{revealed ? t("hidePassword") : t("showPassword")}</span>
          </button>
        ) : null}
      </div>
      {hint && !error ? (
        <p id={hintId} className="text-xs text-muted">
          {hint}
        </p>
      ) : null}
      {error ? (
        <p id={errorId} role="alert" className="text-xs font-semibold text-danger-ink">
          {error}
        </p>
      ) : null}
    </div>
  );
}
