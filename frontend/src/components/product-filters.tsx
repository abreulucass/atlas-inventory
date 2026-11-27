"use client";

import type { ChangeEvent } from "react";

import { FormField } from "@/components/form-field";
import { SelectField } from "@/components/select-field";
import { Button } from "@/components/ui/button";
import type { Category } from "@/lib/api";
import type { ProductParams } from "@/lib/catalog-query";

/**
 * `categories`: every category, archived included. No `pagina` field: a submit returns to page 1.
 * Changing a select applies the filter at once; "Filtrar" submits the search text.
 */
export function ProductFilters({
  params,
  categories,
}: {
  params: ProductParams;
  categories: Category[];
}) {
  const apply = (event: ChangeEvent<HTMLSelectElement>) => event.currentTarget.form?.requestSubmit();
  return (
    <form
      method="get"
      action="/produtos"
      className="grid gap-4 sm:grid-cols-[1fr_auto_auto_auto] sm:items-end"
    >
      <FormField
        id="q"
        type="search"
        label="Buscar por nome ou SKU"
        autoComplete="off"
        defaultValue={params.q}
      />
      <SelectField id="categoria" label="Categoria" defaultValue={params.categoria ?? ""} onChange={apply}>
        <option value="">Todas</option>
        {categories.map((category) => (
          <option key={category.id} value={category.id}>
            {category.status === "ARCHIVED" ? `${category.name} (arquivada)` : category.name}
          </option>
        ))}
      </SelectField>
      <SelectField id="status" label="Status" defaultValue={params.status} onChange={apply}>
        <option value="ativos">Ativos</option>
        <option value="arquivados">Arquivados</option>
        <option value="todos">Todos</option>
      </SelectField>
      <Button type="submit">Filtrar</Button>
    </form>
  );
}
