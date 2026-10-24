import type { ButtonHTMLAttributes } from "react";

type Variant = "primary" | "secondary" | "danger";

const styles: Record<Variant, string> = {
  primary: "bg-primary text-white hover:bg-primary-strong disabled:opacity-60",
  secondary: "bg-surface text-ink border border-line-strong hover:bg-canvas disabled:opacity-60",
  danger: "bg-surface text-danger border border-danger hover:bg-danger-soft disabled:opacity-60",
};

export function Button({
  variant = "primary",
  className = "",
  type = "button",
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: Variant }) {
  // min-h-11 = 44px, the smallest comfortable touch target.
  return (
    <button
      type={type}
      className={`inline-flex min-h-11 items-center justify-center rounded-[10px] px-4 text-sm font-bold transition-colors ${styles[variant]} ${className}`}
      {...props}
    />
  );
}
