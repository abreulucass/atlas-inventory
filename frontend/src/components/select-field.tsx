import type { ComponentProps } from "react";

import { Label } from "@/components/ui/label";
import { cn } from "cn";

type Props = ComponentProps<"select"> & {
  id: string;
  label: string;
  error?: string;
};

// Native <select> styled like <Input>: submits its value in the form with no extra wiring.
export function SelectField({ id, label, error, className, children, ...props }: Props) {
  const errorId = `${id}-error`;
  return (
    <div className="grid gap-2">
      <Label htmlFor={id}>{label}</Label>
      <select
        id={id}
        name={id}
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? errorId : undefined}
        className={cn(
          "h-8 w-full min-w-0 rounded-lg border border-input bg-transparent px-2.5 py-1 text-base outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 disabled:cursor-not-allowed disabled:opacity-50 aria-invalid:border-destructive aria-invalid:ring-3 aria-invalid:ring-destructive/20 md:text-sm dark:bg-input/30",
          className,
        )}
        {...props}
      >
        {children}
      </select>
      {error && (
        <p id={errorId} className="text-sm text-destructive">
          {error}
        </p>
      )}
    </div>
  );
}
