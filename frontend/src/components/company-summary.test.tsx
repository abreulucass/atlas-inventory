import { render, screen } from "@testing-library/react";
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
  createWarehouse: vi.fn(),
}));

import type { Company } from "@/lib/api";

import { CompanySummary } from "./company-summary";

const active: Company = { id: "c1", name: "Acme", cnpj: "11222333000181", status: "ACTIVE" };
const archived: Company = { ...active, status: "ARCHIVED" };

describe("CompanySummary", () => {
  it("shows name, CNPJ and status", () => {
    const { rerender } = render(<CompanySummary company={active} canManage={false} />);
    expect(screen.getByRole("heading", { name: "Acme" })).toBeInTheDocument();
    expect(screen.getByText(/11\.222\.333\/0001-81/)).toBeInTheDocument();
    expect(screen.getByText("Ativa")).toBeInTheDocument();

    rerender(<CompanySummary company={{ ...archived, cnpj: null }} canManage={false} />);
    expect(screen.getByText("Arquivada")).toBeInTheDocument();
    expect(screen.getByText(/—/)).toBeInTheDocument();
  });

  it("links back to the companies list", () => {
    render(<CompanySummary company={active} canManage={false} />);
    expect(screen.getByRole("link", { name: "Voltar para empresas" })).toHaveAttribute("href", "/empresas");
  });

  it("shows the new warehouse button to managers of an active company", () => {
    render(<CompanySummary company={active} canManage />);
    expect(screen.getByRole("button", { name: "Novo depósito" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Arquivar" })).toBeInTheDocument();
  });

  it("hides the new warehouse button from members and archived companies", () => {
    const { rerender } = render(<CompanySummary company={active} canManage={false} />);
    expect(screen.queryByRole("button", { name: "Novo depósito" })).not.toBeInTheDocument();

    rerender(<CompanySummary company={archived} canManage />);
    expect(screen.queryByRole("button", { name: "Novo depósito" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Desarquivar" })).toBeInTheDocument();
  });
});
