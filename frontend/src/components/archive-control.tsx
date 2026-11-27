"use client";

import { useState, useTransition } from "react";

import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Button } from "@/components/ui/button";
import {
  archiveCategory,
  archiveProduct,
  unarchiveCategory,
  unarchiveProduct,
} from "@/lib/catalog-actions";
import {
  archiveCompany,
  archiveWarehouse,
  unarchiveCompany,
  unarchiveWarehouse,
  type ArchiveState,
} from "@/lib/company-actions";

type Props = { archived: boolean } & (
  | { kind: "company" | "warehouse"; companyId: string; id?: string }
  | { kind: "category" | "product"; id: string; companyId?: never }
);

const TITLES = {
  company: "Arquivar empresa?",
  warehouse: "Arquivar depósito?",
  category: "Arquivar categoria?",
  product: "Arquivar produto?",
} as const;

export function ArchiveControl({ kind, companyId, id, archived }: Props) {
  const [open, setOpen] = useState(false);
  const [pending, startTransition] = useTransition();
  const [error, setError] = useState<string>();

  const call = (archive: boolean): Promise<ArchiveState> => {
    if (kind === "category") return (archive ? archiveCategory : unarchiveCategory)(id);
    if (kind === "product") return (archive ? archiveProduct : unarchiveProduct)(id);
    if (kind === "company") return (archive ? archiveCompany : unarchiveCompany)(companyId ?? "");
    return (archive ? archiveWarehouse : unarchiveWarehouse)(companyId ?? "", id ?? "");
  };

  const run = (archive: boolean) => {
    setError(undefined);
    setOpen(false);
    startTransition(async () => {
      const result = await call(archive);
      if ("error" in result) setError(result.error);
    });
  };

  return (
    <div className="flex flex-col items-start gap-1">
      {archived ? (
        <Button variant="outline" size="sm" disabled={pending} onClick={() => run(false)}>
          Desarquivar
        </Button>
      ) : (
        <Button variant="outline" size="sm" disabled={pending} onClick={() => setOpen(true)}>
          Arquivar
        </Button>
      )}
      {error && (
        <p role="alert" className="text-xs text-destructive">
          {error}
        </p>
      )}
      <AlertDialog open={open} onOpenChange={setOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{TITLES[kind]}</AlertDialogTitle>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancelar</AlertDialogCancel>
            <AlertDialogAction onClick={() => run(true)}>Arquivar</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
