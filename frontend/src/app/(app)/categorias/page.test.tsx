import { screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("server-only", () => ({}));
vi.mock("@/lib/catalog-actions", () => ({
  createCategory: vi.fn(),
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

const getCategories = vi.fn();
const getMe = vi.fn();
vi.mock("@/lib/api", () => ({
  getCategories: (...args: unknown[]) => getCategories(...args),
  getMe: () => getMe(),
}));

import { renderServer } from "@/test/render-server";

import CategoriesPage from "./page";

async function renderPage(searchParams: Record<string, string | string[]>) {
  await renderServer(<CategoriesPage searchParams={Promise.resolve(searchParams) as never} />);
}

beforeEach(() => {
  getCategories.mockReset().mockResolvedValue([{ id: "k1", name: "Bebidas", status: "ACTIVE" }]);
  getMe.mockReset().mockResolvedValue({ role: "OWNER" });
});

describe("CategoriesPage", () => {
  it.each<Record<string, string | string[]>>([{}, { arquivadas: "true" }, { arquivadas: "" }, { arquivadas: "0" }, { arquivadas: ["1", "1"] }])(
    "requests active categories for %j",
    async (params) => {
      await renderPage(params);
      expect(getCategories).toHaveBeenCalledExactlyOnceWith(false);
      expect(screen.getByRole("link", { name: "Mostrar arquivadas" })).toHaveAttribute(
        "href",
        "/categorias?arquivadas=1",
      );
    },
  );

  it("requests archived categories only for arquivadas=1", async () => {
    await renderPage({ arquivadas: "1" });
    expect(getCategories).toHaveBeenCalledExactlyOnceWith(true);
    expect(screen.getByRole("link", { name: "Mostrar arquivadas" })).toHaveAttribute("href", "/categorias");
  });

  it.each([
    ["OWNER", true],
    ["ADMIN", true],
    ["MEMBER", false],
  ])("%s write controls visible: %s", async (role, visible) => {
    getMe.mockResolvedValue({ role });
    await renderPage({});
    expect(screen.queryByRole("button", { name: "Nova categoria" }) !== null).toBe(visible);
    expect(screen.queryByRole("button", { name: "Arquivar" }) !== null).toBe(visible);
  });
});
