import Link from "next/link";

import { cn } from "@/lib/utils";

/** `href` already points at the toggled state; `checked` is the current one. */
export function ArchivedFilter({ href, checked, label }: { href: string; checked: boolean; label: string }) {
  return (
    <Link
      href={href}
      aria-pressed={checked}
      className={cn(
        "inline-flex h-8 items-center rounded-lg border px-3 text-sm",
        checked ? "bg-accent text-accent-foreground" : "hover:bg-accent/50",
      )}
    >
      {label}
    </Link>
  );
}
