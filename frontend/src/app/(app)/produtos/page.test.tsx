import { render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("server-only", () => ({}));
vi.mock("@/lib/catalog-actions", () => ({
  createProduct: vi.fn(),
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

const getProducts = vi.fn();
const getCategories = vi.fn();
const getMe = vi.fn();
vi.mock("@/lib/api", () => ({
  getProducts: (...args: unknown[]) => getProducts(...args),
  getCategories: (...args: unknown[]) => getCategories(...args),
  getMe: () => getMe(),
}));

import { renderServer } from "@/test/render-server";

import ProductsPage from "./page";

const CAT = "0b8f2f4e-6f1c-4c43-9a43-2f6f9a1b7c10";

async function renderPage(searchParams: Record<string, string | string[]>) {
  await renderServer(<ProductsPage searchParams={Promise.resolve(searchParams)} />);
}

beforeEach(() => {
  getProducts.mockReset().mockResolvedValue({
    items: [
      { id: "p1", sku: "A-1", name: "Água", unit: "L", fractional: true, categoryId: CAT, status: "ACTIVE", version: 0 },
    ],
    page: 0,
    size: 20,
    totalItems: 1,
    totalPages: 1,
  });
  getCategories.mockReset().mockResolvedValue([{ id: CAT, name: "Bebidas", status: "ACTIVE" }]);
  getMe.mockReset().mockResolvedValue({ role: "OWNER" });
});

describe("ProductsPage", () => {
  it("requests products from the url and every category", async () => {
    await renderPage({ q: "a b", categoria: CAT, status: "todos", pagina: "2", foo: "x" });
    expect(getProducts).toHaveBeenCalledExactlyOnceWith(
      `q=a+b&categoryId=${CAT}&status=ALL&page=1`,
    );
    expect(getCategories).toHaveBeenCalledExactlyOnceWith(true);
    expect(screen.getByRole("heading", { name: "Produtos" })).toBeInTheDocument();
    expect(screen.getByText("Água")).toBeInTheDocument();
  });

  it("requests the default list for no params and says so when empty", async () => {
    getProducts.mockResolvedValue({ items: [], page: 0, size: 20, totalItems: 0, totalPages: 0 });
    await renderPage({});
    expect(getProducts).toHaveBeenCalledExactlyOnceWith("page=0");
    expect(screen.getByText("Nenhum produto cadastrado.")).toBeInTheDocument();
  });

  it.each<[string, Record<string, string>, string]>([
    ["no filter", {}, "Nenhum produto cadastrado."],
    ["only a page", { pagina: "2" }, "Nenhum produto cadastrado."],
    ["a blank search", { q: "   " }, "Nenhum produto cadastrado."],
    ["a search", { q: "zzz" }, "Nenhum produto encontrado."],
    ["only a category", { categoria: CAT }, "Nenhum produto encontrado."],
    ["only arquivados", { status: "arquivados" }, "Nenhum produto encontrado."],
    ["only todos", { status: "todos" }, "Nenhum produto encontrado."],
  ])("tells an empty catalog from an empty search for %s", async (_name, params, message) => {
    getProducts.mockResolvedValue({ items: [], page: 0, size: 20, totalItems: 0, totalPages: 0 });
    await renderPage(params);
    expect(screen.getByText(message)).toBeInTheDocument();
    expect(screen.queryByText(message.includes("cadastrado") ? "Nenhum produto encontrado." : "Nenhum produto cadastrado.")).toBeNull();
  });

  it.each([
    ["OWNER", true],
    ["ADMIN", true],
    ["MEMBER", false],
  ])("%s write controls visible: %s", async (role, visible) => {
    getMe.mockResolvedValue({ role });
    await renderPage({});
    expect(screen.queryByRole("button", { name: "Novo produto" }) !== null).toBe(visible);
    expect(screen.queryByRole("button", { name: "Editar" }) !== null).toBe(visible);
  });

  it("shows skeletons for the header, filters and table until the data arrives", () => {
    getMe.mockReturnValue(new Promise(() => {}));
    const { container } = render(<ProductsPage searchParams={Promise.resolve({})} />);
    expect(container.querySelectorAll('[data-slot="skeleton"]')).toHaveLength(3);
    expect(container.querySelector("table")).toBeNull();
  });
});
