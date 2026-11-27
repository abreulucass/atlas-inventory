"use client";

import { CheckIcon, ChevronsUpDownIcon } from "lucide-react";
import { useState, useTransition } from "react";

import { buttonVariants } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import type { Organization } from "@/lib/api";
import { switchOrganization } from "@/lib/auth-actions";
import { cn } from "@/lib/utils";

export function OrganizationSwitcher({
  organizations,
  activeId,
}: {
  organizations: Organization[];
  activeId: string;
}) {
  const [pending, startTransition] = useTransition();
  const [error, setError] = useState<string>();
  const active = organizations.find((o) => o.id === activeId);

  if (organizations.length <= 1) {
    return <span className="max-w-40 truncate px-2 text-sm font-medium">{active?.name}</span>;
  }

  const select = (id: string) => {
    if (id === activeId) return;
    setError(undefined);
    startTransition(async () => {
      const result = await switchOrganization(id);
      if (result?.error) setError(result.error);
    });
  };

  return (
    <div className="flex min-w-0 items-center gap-2">
      <DropdownMenu>
        <DropdownMenuTrigger
          className={cn(buttonVariants({ variant: "ghost", size: "sm" }), "max-w-40 sm:max-w-56")}
        >
          <span className="truncate">{active?.name}</span>
          <ChevronsUpDownIcon />
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-64">
          {organizations.map((o) => (
            <DropdownMenuItem
              key={o.id}
              disabled={pending}
              closeOnClick={false}
              aria-current={o.id === activeId ? "true" : undefined}
              onClick={() => select(o.id)}
            >
              <span className="truncate">{o.name}</span>
              {o.status === "ARCHIVED" && (
                <span className="rounded-full border px-2 py-0.5 text-xs text-muted-foreground">
                  Arquivada
                </span>
              )}
              {o.id === activeId && <CheckIcon className="ml-auto" />}
            </DropdownMenuItem>
          ))}
        </DropdownMenuContent>
      </DropdownMenu>
      {error && (
        <p role="alert" className="max-w-48 truncate text-xs text-destructive" title={error}>
          {error}
        </p>
      )}
    </div>
  );
}
