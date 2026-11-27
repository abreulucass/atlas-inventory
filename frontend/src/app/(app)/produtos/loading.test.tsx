import { render, screen } from "@testing-library/react";
import { renderToReadableStream } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";

vi.mock("server-only", () => ({}));
vi.mock("@/lib/catalog-actions", () => ({
  createProduct: vi.fn(),
  updateProduct: vi.fn(),
  archiveProduct: vi.fn(),
  unarchiveProduct: vi.fn(),
}));
vi.mock("@/lib/company-actions", () => ({
  archiveCompany: vi.fn(),
  unarchiveCompany: vi.fn(),
  archiveWarehouse: vi.fn(),
  unarchiveWarehouse: vi.fn(),
}));

const getProducts = vi.fn();
vi.mock("@/lib/api", () => ({
  getProducts: (...args: unknown[]) => getProducts(...args),
  getCategories: async () => [{ id: "k1", name: "Bebidas", status: "ACTIVE" }],
  getMe: async () => ({ role: "OWNER" }),
}));

import { renderServer } from "@/test/render-server";

import ProductsLoading from "./loading";
import ProductsPage from "./page";

// Everything the stream has flushed so far; it stays open while the list read is pending.
async function drain(reader: ReadableStreamDefaultReader<Uint8Array>) {
  const decoder = new TextDecoder();
  let html = "";
  for (;;) {
    const chunk = await Promise.race([reader.read(), new Promise<null>((r) => setTimeout(() => r(null), 50))]);
    if (!chunk || chunk.done) return html;
    html += decoder.decode(chunk.value, { stream: true });
  }
}

const skeletons = () => document.body.querySelectorAll('[data-slot="skeleton"]');

describe("ProductsLoading", () => {
  it("renders the table skeleton", async () => {
    render(<ProductsLoading />);
    const loading = screen.getByTestId("products-skeleton").querySelectorAll('[data-slot="skeleton"]');
    expect(loading).toHaveLength(3);
    expect(loading[2]).toHaveClass("h-40", "w-full");
    document.body.innerHTML = "";

    // The page itself, with only the list read still pending: header and filters are already there.
    let resolve: (page: unknown) => void = () => {};
    getProducts.mockReturnValue(new Promise((r) => (resolve = r)));
    const stream = await renderToReadableStream(<ProductsPage searchParams={Promise.resolve({ q: "a b" })} />);
    // Let every read except the list settle before the first flush.
    await new Promise((r) => setTimeout(r, 50));
    const reader = stream.getReader();
    document.body.innerHTML = await drain(reader);
    expect(screen.getByRole("heading", { name: "Produtos" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Novo produto" })).toBeInTheDocument();
    expect(screen.getByLabelText("Buscar por nome ou SKU")).toHaveValue("a b");
    expect(screen.getByRole("button", { name: "Filtrar" })).toBeInTheDocument();
    expect(screen.queryByRole("table")).toBeNull();
    expect(skeletons()).toHaveLength(1);
    expect(skeletons()[0]).toHaveClass("h-40", "w-full");

    // Once the list resolves the table replaces the skeleton and the filters stay.
    const page = {
      items: [
        { id: "p1", sku: "A-1", name: "Água", unit: "L", fractional: true, categoryId: null, status: "ACTIVE", version: 0 },
      ],
      page: 0,
      size: 20,
      totalItems: 1,
      totalPages: 1,
    };
    resolve(page);
    await reader.cancel();
    getProducts.mockResolvedValue(page);
    await renderServer(<ProductsPage searchParams={Promise.resolve({ q: "a b" })} />);
    expect(screen.getByRole("table")).toBeInTheDocument();
    expect(screen.getByText("Água")).toBeInTheDocument();
    expect(skeletons()).toHaveLength(0);
    expect(screen.getByLabelText("Buscar por nome ou SKU")).toHaveValue("a b");
  });
});
