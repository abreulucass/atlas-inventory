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
// Dialogs always open, so the server render shows what each one offers.
vi.mock("@/components/ui/dialog", () => ({
  Dialog: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  DialogTrigger: ({ children }: { children: React.ReactNode }) => <button>{children}</button>,
  DialogContent: ({ children }: { children: React.ReactNode }) => <div role="dialog">{children}</div>,
  DialogHeader: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  DialogTitle: ({ children }: { children: React.ReactNode }) => <h2>{children}</h2>,
}));

vi.mock("@/lib/api", () => ({
  getMe: async () => ({ role: "OWNER" }),
  getProducts: async () => ({
    items: [
      { id: "p1", sku: "A-1", name: "Água", unit: "L", fractional: true, categoryId: "k9", status: "ACTIVE", version: 0 },
    ],
    page: 0,
    size: 20,
    totalItems: 1,
    totalPages: 1,
  }),
  getCategories: async () => [
    { id: "k1", name: "Bebidas", status: "ACTIVE" },
    { id: "k9", name: "Antiga", status: "ARCHIVED" },
  ],
}));

import { renderServer } from "@/test/render-server";

import ProductsPage from "./page";

const options = (select: HTMLElement) =>
  Array.from(select.querySelectorAll("option")).map((o) => [o.value, o.textContent]);

describe("ProductsPage create and edit dialogs", () => {
  it("offers only active categories when creating, and the current archived one when editing", async () => {
    await renderServer(<ProductsPage searchParams={Promise.resolve({})} />);
    // Streamed segments stay hidden in the static HTML, so query the DOM rather than the a11y tree.
    const [create, edit] = Array.from(document.querySelectorAll<HTMLElement>('[role="dialog"]'));
    expect(options(create.querySelector<HTMLElement>('select[name="categoryId"]')!)).toEqual([
      ["", "Sem categoria"],
      ["k1", "Bebidas"],
    ]);
    expect(options(edit.querySelector<HTMLElement>('select[name="categoryId"]')!)).toEqual([
      ["", "Sem categoria"],
      ["k9", "Antiga"],
      ["k1", "Bebidas"],
    ]);
  });
});
