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
import type { Category, Product } from "@/lib/api";
import { updateProduct } from "@/lib/catalog-actions";
import type { CreateState } from "@/lib/company-actions";
import { formatUnit } from "@/lib/format";

type Props = {
  product: Product;
  /** Active categories only. */
  categories: Category[];
  /** The product's category when it is archived: offered for this product only. */
  currentCategory?: Category;
};

export function EditProductDialog({ product, categories, currentCategory }: Props) {
  const [open, setOpen] = useState(false);
  const [state, formAction] = useActionState<CreateState, FormData>(async (prev, data) => {
    const next = await updateProduct(prev, data);
    if (next.ok) setOpen(false);
    return next;
  }, {});
  const errors = state.fieldErrors ?? {};
  const categoryId = state.values?.categoryId ?? product.categoryId ?? "";
  const options = currentCategory ? [currentCategory, ...categories] : categories;

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger render={<Button variant="outline" size="sm" />}>Editar</DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Editar produto</DialogTitle>
        </DialogHeader>
        <form action={formAction} noValidate className="grid gap-4">
          {state.formError && (
            <p role="alert" className="text-sm text-destructive">
              {state.formError}
            </p>
          )}
          <input type="hidden" name="id" value={product.id} />
          <input type="hidden" name="version" value={product.version} />
          <dl className="grid grid-cols-2 gap-2 text-sm">
            <div>
              <dt className="text-muted-foreground">SKU</dt>
              <dd>{product.sku}</dd>
            </div>
            <div>
              <dt className="text-muted-foreground">Unidade</dt>
              <dd>{formatUnit(product.unit)}</dd>
            </div>
          </dl>
          <FormField
            id="name"
            label="Nome"
            autoComplete="off"
            defaultValue={state.values?.name ?? product.name}
            error={errors.name}
          />
          {/* key: React resets an uncontrolled select to its first option after the action */}
          <SelectField
            key={categoryId}
            id="categoryId"
            label="Categoria (opcional)"
            defaultValue={categoryId}
            error={errors.categoryId}
          >
            <option value="">Sem categoria</option>
            {options.map((category) => (
              <option key={category.id} value={category.id}>
                {category.name}
              </option>
            ))}
          </SelectField>
          <SubmitButton label="Salvar" pendingLabel="Salvar" />
        </form>
      </DialogContent>
    </Dialog>
  );
}
