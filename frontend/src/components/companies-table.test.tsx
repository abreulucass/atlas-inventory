import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import type { Company } from "@/lib/api";

import { CompaniesTable } from "./companies-table";

const companies: Company[] = [
  { id: "c1", name: "Acme", cnpj: "11222333000181", status: "ACTIVE" },
  { id: "c2", name: "Velha", cnpj: null, status: "ARCHIVED" },
];

describe("CompaniesTable", () => {
  it("shows name, CNPJ and status for each company", () => {
    render(<CompaniesTable companies={companies} />);
    const [, active, archived] = screen.getAllByRole("row");
    expect(within(active).getByText("Acme")).toBeInTheDocument();
    expect(within(active).getByText("11.222.333/0001-81")).toBeInTheDocument();
    expect(within(active).getByText("Ativa")).toBeInTheDocument();
    expect(within(archived).getByText("Velha")).toBeInTheDocument();
    expect(within(archived).getByText("—")).toBeInTheDocument();
    expect(within(archived).getByText("Arquivada")).toHaveAttribute("data-slot", "badge");
  });

  it("shows the empty message", () => {
    render(<CompaniesTable companies={[]} />);
    expect(screen.getByText("Nenhuma empresa cadastrada.")).toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });

  it("links each company to its page", () => {
    render(<CompaniesTable companies={companies} />);
    expect(screen.getByRole("link", { name: "Acme" })).toHaveAttribute("href", "/empresas/c1");
    expect(screen.getByRole("link", { name: "Velha" })).toHaveAttribute("href", "/empresas/c2");
  });
});
