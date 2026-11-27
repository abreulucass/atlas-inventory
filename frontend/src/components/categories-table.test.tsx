import { render, screen, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

vi.mock("@/lib/catalog-actions", () => ({
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

import type { Category } from "@/lib/api";
import { canManage } from "@/lib/roles";

import { CategoriesTable } from "./categories-table";

const categories: Category[] = [
  { id: "k1", name: "Bebidas", status: "ACTIVE" },
  { id: "k2", name: "Antiga", status: "ARCHIVED" },
];

describe("CategoriesTable", () => {
  it("shows name and status of each category", () => {
    render(<CategoriesTable categories={categories} canManage={false} />);
    const [, active, archived] = screen.getAllByRole("row");
    expect(within(active).getByText("Bebidas")).toBeInTheDocument();
    expect(within(active).getByText("Ativa")).toBeInTheDocument();
    expect(within(archived).getByText("Antiga")).toBeInTheDocument();
    expect(within(archived).getByText("Arquivada")).toBeInTheDocument();
  });

  it("shows the Arquivada badge on archived categories", () => {
    render(<CategoriesTable categories={categories} canManage={false} />);
    expect(screen.getByText("Arquivada")).toHaveAttribute("data-slot", "badge");
    expect(screen.getByText("Ativa")).not.toHaveAttribute("data-slot", "badge");
  });

  it("shows the empty message", () => {
    render(<CategoriesTable categories={[]} canManage />);
    expect(screen.getByText("Nenhuma categoria cadastrada.")).toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });

  it("shows Arquivar on active and Desarquivar on archived rows for OWNER and ADMIN", () => {
    for (const role of ["OWNER", "ADMIN"] as const) {
      const { unmount } = render(<CategoriesTable categories={categories} canManage={canManage(role)} />);
      const [, active, archived] = screen.getAllByRole("row");
      expect(within(active).getByRole("button", { name: "Arquivar" })).toBeInTheDocument();
      expect(within(archived).getByRole("button", { name: "Desarquivar" })).toBeInTheDocument();
      unmount();
    }
  });

  it("shows no archive control for MEMBER", () => {
    render(<CategoriesTable categories={categories} canManage={canManage("MEMBER")} />);
    expect(screen.queryByRole("button", { name: "Arquivar" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Desarquivar" })).not.toBeInTheDocument();
  });
});
