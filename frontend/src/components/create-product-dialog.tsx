"use client";

import { useActionState, useState } from "react";

import { FormField } from "@/components/form-field";
import { SelectField } from "@/components/select-field";
import { SubmitButton } from "@/components/submit-button";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import type { Category } from "@/lib/api";
import { createProduct } from "@/lib/catalog-actions";
import type { CreateState } from "@/lib/company-actions";
import { formatUnit, UNITS } from "@/lib/format";
import { validateProduct } from "@/lib/validation";

/** `categories`: active ones only. */
export function CreateProductDialog({ categories }: { categories: Category[] }) {
  const [open, setOpen] = useState(false);
  const [state, formAction] = useActionState<CreateState, FormData>(async (prev, data) => {
    // Same rules as the Server Action, run first so a bad value makes no request.
    const text = (name: string) => String(data.get(name) ?? "");
    const values = { sku: text("sku"), name: text("name"), unit: text("unit"), categoryId: text("categoryId") };
    const fieldErrors = validateProduct(values);
    if (Object.keys(fieldErrors).length > 0) return { fieldErrors, values };
    const next = await createProduct(prev, data);
    if (next.ok) setOpen(false);
    return next;
  }, {});
  const errors = state.fieldErrors ?? {};

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger render={<Button />}>Novo produto</DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Novo produto</DialogTitle>
        </DialogHeader>
        <form action={formAction} noValidate className="grid gap-4">
          {state.formError && (
            <p role="alert" className="text-sm text-destructive">
              {state.formError}
            </p>
          )}
          <FormField
            id="sku"
            label="SKU"
            autoComplete="off"
            defaultValue={state.values?.sku}
            error={errors.sku}
          />
          <FormField
            id="name"
            label="Nome"
            autoComplete="off"
            defaultValue={state.values?.name}
            error={errors.name}
          />
          {/* key: React resets an uncontrolled select to its first option after the action */}
          <SelectField
            key={`unit-${state.values?.unit ?? ""}`}
            id="unit"
            label="Unidade"
            defaultValue={state.values?.unit ?? ""}
            error={errors.unit}
          >
            <option value="">Selecione</option>
            {UNITS.map((unit) => (
              <option key={unit} value={unit}>
                {formatUnit(unit)}
              </option>
            ))}
          </SelectField>
          <SelectField
            key={`cat-${state.values?.categoryId ?? ""}`}
            id="categoryId"
            label="Categoria (opcional)"
            defaultValue={state.values?.categoryId ?? ""}
            error={errors.categoryId}
          >
            <option value="">Sem categoria</option>
            {categories.map((category) => (
              <option key={category.id} value={category.id}>
                {category.name}
              </option>
            ))}
          </SelectField>
          <SubmitButton label="Criar produto" pendingLabel="Criar produto" />
        </form>
      </DialogContent>
    </Dialog>
  );
}
