import { render, screen, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

vi.mock("@/lib/catalog-actions", () => ({
  updateProduct: vi.fn(),
  archiveCategory: vi.fn(),
  unarchiveCategory: vi.fn(),
  archiveProduct: vi.fn(),
  unarchiveProduct: vi.fn(),
}));
vi.mock("@/lib/company-actions", () => ({
  archiveCompany: vi.fn(),
  unarchiveCompany: vi.fn(),
  archiveWarehouse: vi.fn(),
  unarchiveWarehouse: vi.fn(),
}));

import type { Category, Product } from "@/lib/api";
import { canManage } from "@/lib/roles";

import { ProductsTable } from "./products-table";

const categories: Category[] = [
  { id: "k1", name: "Bebidas", status: "ACTIVE" },
  { id: "k2", name: "Antiga", status: "ARCHIVED" },
];
const product = (over: Partial<Product>): Product => ({
  id: "p1",
  sku: "AGUA-1",
  name: "Água",
  unit: "L",
  fractional: true,
  categoryId: "k1",
  status: "ACTIVE",
  version: 0,
  ...over,
});
const products: Product[] = [
  product({}),
  product({ id: "p2", sku: "CAIXA-2", name: "Caixa", unit: "CX", categoryId: null, status: "ARCHIVED" }),
  product({ id: "p3", sku: "VELHO-3", name: "Velho", unit: "KG", categoryId: "k2" }),
];

const rows = () => screen.getAllByRole("row").slice(1);

describe("ProductsTable", () => {
  it("shows SKU, name, category, unit and status in the API order", () => {
    render(<ProductsTable products={products} categories={categories} canManage={false} filtered={false} />);
    const [first, second] = rows();
    expect(within(first).getAllByRole("cell").map((c) => c.textContent)).toEqual([
      "AGUA-1",
      "Água",
      "Bebidas",
      "Litro (L)",
      "Ativo",
    ]);
    expect(within(second).getAllByRole("cell").map((c) => c.textContent)).toEqual([
      "CAIXA-2",
      "Caixa",
      "—",
      "Caixa (CX)",
      "Arquivado",
    ]);
    expect(rows().map((r) => within(r).getAllByRole("cell")[0].textContent)).toEqual([
      "AGUA-1",
      "CAIXA-2",
      "VELHO-3",
    ]);
  });

  it("shows the Arquivado badge on archived products", () => {
    render(<ProductsTable products={products} categories={categories} canManage={false} filtered={false} />);
    expect(screen.getByText("Arquivado")).toHaveAttribute("data-slot", "badge");
  });

  it("shows a dash for no category and names an archived category", () => {
    render(<ProductsTable products={products} categories={categories} canManage={false} filtered={false} />);
    const [, none, archivedCategory] = rows();
    expect(within(none).getByText("—")).toBeInTheDocument();
    expect(within(archivedCategory).getByText("Antiga")).toBeInTheDocument();
  });

  it("tells an empty catalog from an empty search", () => {
    const { rerender } = render(
      <ProductsTable products={[]} categories={categories} canManage filtered={false} />,
    );
    expect(screen.getByText("Nenhum produto cadastrado.")).toBeInTheDocument();
    rerender(<ProductsTable products={[]} categories={categories} canManage filtered />);
    expect(screen.getByText("Nenhum produto encontrado.")).toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });

  it("gates Editar and archive controls by role and status", () => {
    for (const role of ["OWNER", "ADMIN"] as const) {
      const { unmount } = render(
        <ProductsTable products={products} categories={categories} canManage={canManage(role)} filtered={false} />,
      );
      const [active, archived] = rows();
      expect(within(active).getByRole("button", { name: "Editar" })).toBeInTheDocument();
      expect(within(active).getByRole("button", { name: "Arquivar" })).toBeInTheDocument();
      expect(within(archived).getByRole("button", { name: "Desarquivar" })).toBeInTheDocument();
      expect(within(archived).queryByRole("button", { name: "Editar" })).not.toBeInTheDocument();
      unmount();
    }
    render(
      <ProductsTable products={products} categories={categories} canManage={canManage("MEMBER")} filtered={false} />,
    );
    for (const name of ["Editar", "Arquivar", "Desarquivar"]) {
      expect(screen.queryByRole("button", { name })).not.toBeInTheDocument();
    }
  });
});
