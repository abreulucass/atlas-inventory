import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import type { Category } from "@/lib/api";
import type { ProductParams } from "@/lib/catalog-query";
import { ProductFilters } from "./product-filters";

const CATEGORIES: Category[] = [
  { id: "k1", name: "Bebidas", status: "ACTIVE" },
  { id: "k2", name: "Antiga", status: "ARCHIVED" },
];
const DEFAULTS: ProductParams = { q: undefined, categoria: undefined, status: "ativos", pagina: "1" };

const renderFilters = (params: Partial<ProductParams> = {}) =>
  render(<ProductFilters params={{ ...DEFAULTS, ...params }} categories={CATEGORIES} />);

const optionTexts = (label: string) =>
  within(screen.getByLabelText(label)).getAllByRole("option").map((o) => o.textContent);

describe("ProductFilters", () => {
  it("submits q, categoria and status by GET to /produtos", () => {
    const { container } = renderFilters();
    const form = container.querySelector("form")!;
    expect(form).toHaveAttribute("method", "get");
    expect(form).toHaveAttribute("action", "/produtos");
    expect(screen.getByLabelText("Buscar por nome ou SKU")).toHaveAttribute("name", "q");
    expect(screen.getByLabelText("Categoria")).toHaveAttribute("name", "categoria");
    expect(screen.getByLabelText("Status")).toHaveAttribute("name", "status");
    expect(optionTexts("Categoria")[0]).toBe("Todas");
    expect(optionTexts("Status")).toEqual(["Ativos", "Arquivados", "Todos"]);
    expect(screen.getByRole("button", { name: "Filtrar" })).toHaveAttribute("type", "submit");
  });

  it("shows the current filters back", () => {
    renderFilters({ q: "ab", categoria: "k1", status: "todos" });
    expect(screen.getByLabelText("Buscar por nome ou SKU")).toHaveValue("ab");
    expect(screen.getByLabelText("Categoria")).toHaveValue("k1");
    expect(screen.getByLabelText("Status")).toHaveValue("todos");
  });

  it("lists archived categories with the (arquivada) suffix", () => {
    renderFilters();
    expect(optionTexts("Categoria")).toEqual(["Todas", "Bebidas", "Antiga (arquivada)"]);
  });

  it("shows the current search back in the field", () => {
    renderFilters({ q: "50% & mais ou menos" });
    expect(screen.getByLabelText("Buscar por nome ou SKU")).toHaveValue("50% & mais ou menos");
  });

  it("sends no pagina field", () => {
    const { container } = renderFilters({ pagina: "4" });
    const form = container.querySelector("form")!;
    expect(form.elements.namedItem("pagina")).toBeNull();
    expect([...new FormData(form).keys()].sort()).toEqual(["categoria", "q", "status"]);
  });
});
