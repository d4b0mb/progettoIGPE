import { useId, type InputHTMLAttributes, type ReactNode } from "react";

interface FieldProps extends InputHTMLAttributes<HTMLInputElement> {
  label: string;
  error?: string;
  hint?: ReactNode;
}

/** A labelled input. The label, hint and error are all tied to the input for screen readers. */
export function Field({ label, error, hint, className = "", ...props }: FieldProps) {
  const id = useId();
  const hintId = `${id}-hint`;
  const errorId = `${id}-error`;
  const described = [hint ? hintId : null, error ? errorId : null].filter(Boolean).join(" ") || undefined;
  return (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={id} className="text-sm font-bold">
        {label}
      </label>
      <input
        id={id}
        aria-invalid={error ? true : undefined}
        aria-describedby={described}
        className={`min-h-12 rounded-[10px] border bg-surface px-3.5 text-base ${
          error ? "border-danger" : "border-line-strong"
        } ${className}`}
        {...props}
      />
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
