"use client";

import { useActionState, useState } from "react";

import { FormField } from "@/components/form-field";
import { SubmitButton } from "@/components/submit-button";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import { createWarehouse, type CreateState } from "@/lib/company-actions";

export function CreateWarehouseDialog({ companyId }: { companyId: string }) {
  const [open, setOpen] = useState(false);
  const [state, formAction] = useActionState<CreateState, FormData>(async (prev, data) => {
    const next = await createWarehouse(prev, data);
    if (next.ok) setOpen(false);
    return next;
  }, {});
  const errors = state.fieldErrors ?? {};

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger render={<Button />}>Novo depósito</DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Novo depósito</DialogTitle>
        </DialogHeader>
        <form action={formAction} noValidate className="grid gap-4">
          <input type="hidden" name="companyId" value={companyId} />
          {state.formError && (
            <p role="alert" className="text-sm text-destructive">
              {state.formError}
            </p>
          )}
          <FormField
            id="code"
            label="Código"
            autoComplete="off"
            defaultValue={state.values?.code}
            error={errors.code}
          />
          <FormField
            id="name"
            label="Nome"
            autoComplete="off"
            defaultValue={state.values?.name}
            error={errors.name}
          />
          <SubmitButton label="Criar depósito" pendingLabel="Criar depósito" />
        </form>
      </DialogContent>
    </Dialog>
  );
}
