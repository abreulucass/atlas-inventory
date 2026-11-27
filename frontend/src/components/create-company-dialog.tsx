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
import { createCompany, type CreateState } from "@/lib/company-actions";
import { validateCompany } from "@/lib/validation";

export function CreateCompanyDialog() {
  const [open, setOpen] = useState(false);
  const [state, formAction] = useActionState<CreateState, FormData>(async (prev, data) => {
    const name = String(data.get("name") ?? "");
    const fieldErrors = validateCompany({ name });
    if (Object.keys(fieldErrors).length > 0) {
      return { fieldErrors, values: { name, cnpj: String(data.get("cnpj") ?? "") } };
    }
    const next = await createCompany(prev, data);
    if (next.ok) setOpen(false);
    return next;
  }, {});
  const errors = state.fieldErrors ?? {};

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger render={<Button />}>Nova empresa</DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Nova empresa</DialogTitle>
        </DialogHeader>
        <form action={formAction} noValidate className="grid gap-4">
          {state.formError && (
            <p role="alert" className="text-sm text-destructive">
              {state.formError}
            </p>
          )}
          <FormField
            id="name"
            label="Nome"
            autoComplete="off"
            defaultValue={state.values?.name}
            error={errors.name}
          />
          <FormField
            id="cnpj"
            label="CNPJ (opcional)"
            autoComplete="off"
            inputMode="numeric"
            defaultValue={state.values?.cnpj}
            error={errors.cnpj}
          />
          <SubmitButton label="Criar empresa" pendingLabel="Criar empresa" />
        </form>
      </DialogContent>
    </Dialog>
  );
}
