import { render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

vi.mock("server-only", () => ({}));
vi.mock("next/headers", () => ({
  cookies: async () => ({ get: (name: string) => ({ name, value: "tok" }) }),
}));
vi.mock("next/navigation", () => ({ redirect: vi.fn(), notFound: vi.fn() }));

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

import { getProducts, type Category, type Product } from "@/lib/api";
import { hasProductFilter, parseProductParams, productApiQuery } from "@/lib/catalog-query";
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

afterEach(() => vi.unstubAllGlobals());

describe("ProductsTable", () => {
  it("shows SKU, name, category, unit and status in the API order", async () => {
    // The rows come from GET /api/products with the access token, as the page reads them.
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ items: products, page: 0, size: 20, totalItems: 3, totalPages: 1 })),
    );
    vi.stubGlobal("fetch", fetchMock);
    vi.stubEnv("ATLAS_API_URL", "http://api.test");
    const page = await getProducts(productApiQuery(parseProductParams({})));
    vi.unstubAllEnvs();
    expect(fetchMock.mock.calls[0][0]).toBe("http://api.test/api/products?page=0");
    expect(fetchMock.mock.calls[0][1].method ?? "GET").toBe("GET");
    expect(fetchMock.mock.calls[0][1].headers.get("Authorization")).toBe("Bearer tok");
    render(<ProductsTable products={page.items} categories={categories} canManage={false} filtered={false} />);
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
    // The filter state comes from the url: only q, categoria or a non-default status is a search.
    const CAT = "0b8f2f4e-6f1c-4c43-9a43-2f6f9a1b7c10";
    const cases: [Record<string, string>, string][] = [
      [{}, "Nenhum produto cadastrado."],
      [{ pagina: "3" }, "Nenhum produto cadastrado."],
      [{ q: "zzz" }, "Nenhum produto encontrado."],
      [{ categoria: CAT }, "Nenhum produto encontrado."],
      [{ status: "arquivados" }, "Nenhum produto encontrado."],
      [{ status: "todos" }, "Nenhum produto encontrado."],
    ];
    for (const [params, text] of cases) {
      rerender(
        <ProductsTable
          products={[]}
          categories={categories}
          canManage
          filtered={hasProductFilter(parseProductParams(params))}
        />,
      );
      expect(screen.getByText(text), JSON.stringify(params)).toBeInTheDocument();
    }
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
