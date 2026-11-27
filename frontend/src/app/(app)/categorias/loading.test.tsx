import { render, screen } from "@testing-library/react";
import { renderToReadableStream } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";

vi.mock("server-only", () => ({}));
vi.mock("@/lib/catalog-actions", () => ({
  createCategory: vi.fn(),
  archiveCategory: vi.fn(),
  unarchiveCategory: vi.fn(),
}));
vi.mock("@/lib/company-actions", () => ({
  archiveCompany: vi.fn(),
  unarchiveCompany: vi.fn(),
  archiveWarehouse: vi.fn(),
  unarchiveWarehouse: vi.fn(),
}));

const getCategories = vi.fn();
vi.mock("@/lib/api", () => ({
  getCategories: (...args: unknown[]) => getCategories(...args),
  getMe: async () => ({ role: "OWNER" }),
}));

import { renderServer } from "@/test/render-server";

import CategoriesLoading from "./loading";
import CategoriesPage from "./page";

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

describe("CategoriesLoading", () => {
  it("renders the table skeleton", async () => {
    render(<CategoriesLoading />);
    const loading = screen.getByTestId("categories-skeleton").querySelectorAll('[data-slot="skeleton"]');
    expect(loading).toHaveLength(3);
    expect(loading[2]).toHaveClass("h-40", "w-full");
    document.body.innerHTML = "";

    // The page itself, with the list read still pending: the header is there, the table is a skeleton.
    let resolve: (categories: unknown) => void = () => {};
    getCategories.mockReturnValue(new Promise((r) => (resolve = r)));
    const stream = await renderToReadableStream(<CategoriesPage searchParams={Promise.resolve({})} />);
    // Let every read except the list settle before the first flush.
    await new Promise((r) => setTimeout(r, 50));
    const reader = stream.getReader();
    document.body.innerHTML = await drain(reader);
    expect(screen.getByRole("heading", { name: "Categorias" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Nova categoria" })).toBeInTheDocument();
    expect(screen.queryByRole("table")).toBeNull();
    expect(skeletons()).toHaveLength(1);
    expect(skeletons()[0]).toHaveClass("h-40", "w-full");

    // Once the list resolves the table replaces the skeleton.
    const categories = [{ id: "k1", name: "Bebidas", status: "ACTIVE" }];
    resolve(categories);
    await reader.cancel();
    getCategories.mockResolvedValue(categories);
    await renderServer(<CategoriesPage searchParams={Promise.resolve({})} />);
    expect(screen.getByRole("table")).toBeInTheDocument();
    expect(screen.getByText("Bebidas")).toBeInTheDocument();
    expect(skeletons()).toHaveLength(0);
  });
});
