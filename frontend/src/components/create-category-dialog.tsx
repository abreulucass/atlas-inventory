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
import { createCategory } from "@/lib/catalog-actions";
import type { CreateState } from "@/lib/company-actions";

export function CreateCategoryDialog() {
  const [open, setOpen] = useState(false);
  const [state, formAction] = useActionState<CreateState, FormData>(async (prev, data) => {
    const next = await createCategory(prev, data);
    if (next.ok) setOpen(false);
    return next;
  }, {});

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger render={<Button />}>Nova categoria</DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Nova categoria</DialogTitle>
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
            error={state.fieldErrors?.name}
          />
          <SubmitButton label="Criar categoria" pendingLabel="Criar categoria" />
        </form>
      </DialogContent>
    </Dialog>
  );
}
