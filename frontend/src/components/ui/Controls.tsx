import { useId, type ReactNode, type SelectHTMLAttributes, type TextareaHTMLAttributes } from "react";

const control = "min-h-12 w-full rounded-[10px] border bg-surface px-3 text-base";

function Wrapper({ id, label, error, hint, children }: { id: string; label: string; error?: string; hint?: ReactNode; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={id} className="text-sm font-bold">
        {label}
      </label>
      {children}
      {hint && !error ? <p className="text-xs text-muted">{hint}</p> : null}
      {error ? (
        <p role="alert" className="text-xs font-semibold text-danger-ink">
          {error}
        </p>
      ) : null}
    </div>
  );
}

export function SelectField({
  label,
  error,
  hint,
  children,
  ...props
}: SelectHTMLAttributes<HTMLSelectElement> & { label: string; error?: string; hint?: ReactNode }) {
  const id = useId();
  return (
    <Wrapper id={id} label={label} error={error} hint={hint}>
      <select id={id} aria-invalid={error ? true : undefined} className={`${control} ${error ? "border-danger" : "border-line-strong"}`} {...props}>
        {children}
      </select>
    </Wrapper>
  );
}

export function TextareaField({
  label,
  error,
  hint,
  ...props
}: TextareaHTMLAttributes<HTMLTextAreaElement> & { label: string; error?: string; hint?: ReactNode }) {
  const id = useId();
  return (
    <Wrapper id={id} label={label} error={error} hint={hint}>
      <textarea
        id={id}
        rows={3}
        aria-invalid={error ? true : undefined}
        className={`${control} py-2.5 ${error ? "border-danger" : "border-line-strong"}`}
        {...props}
      />
    </Wrapper>
  );
}
