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

import type { Warehouse } from "@/lib/api";

import { WarehousesTable } from "./warehouses-table";

const warehouses: Warehouse[] = [
  { id: "w1", companyId: "c1", name: "Central", code: "CD-01", status: "ACTIVE" },
  { id: "w2", companyId: "c1", name: "Antigo", code: "CD-02", status: "ARCHIVED" },
];

describe("WarehousesTable", () => {
  it("shows code, name and status for each warehouse", () => {
    render(<WarehousesTable warehouses={warehouses} companyId="c1" canManage={false} />);
    const [, active, archived] = screen.getAllByRole("row");
    expect(within(active).getByText("CD-01")).toBeInTheDocument();
    expect(within(active).getByText("Central")).toBeInTheDocument();
    expect(within(active).getByText("Ativo")).toBeInTheDocument();
    expect(within(archived).getByText("CD-02")).toBeInTheDocument();
    expect(within(archived).getByText("Antigo")).toBeInTheDocument();
    expect(within(archived).getByText("Arquivado")).toHaveAttribute("data-slot", "badge");
  });

  it("shows the empty message", () => {
    render(<WarehousesTable warehouses={[]} companyId="c1" canManage />);
    expect(screen.getByText("Nenhum depósito cadastrado.")).toBeInTheDocument();
  });

  it("shows archive controls only to managers", () => {
    const { rerender } = render(<WarehousesTable warehouses={warehouses} companyId="c1" canManage />);
    const [, active, archived] = screen.getAllByRole("row");
    expect(within(active).getByRole("button", { name: "Arquivar" })).toBeInTheDocument();
    expect(within(archived).getByRole("button", { name: "Desarquivar" })).toBeInTheDocument();

    rerender(<WarehousesTable warehouses={warehouses} companyId="c1" canManage={false} />);
    expect(screen.queryByRole("button", { name: "Arquivar" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Desarquivar" })).not.toBeInTheDocument();
  });
});
