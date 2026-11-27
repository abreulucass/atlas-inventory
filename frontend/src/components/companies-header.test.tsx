import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

vi.mock("@/components/create-company-dialog", () => ({
  CreateCompanyDialog: () => <button>Nova empresa</button>,
}));

import { CompaniesHeader } from "./companies-header";

describe("CompaniesHeader", () => {
  it("shows the create button only to managers", () => {
    const { rerender } = render(<CompaniesHeader canManage />);
    expect(screen.getByRole("heading", { name: "Empresas" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Nova empresa" })).toBeInTheDocument();

    rerender(<CompaniesHeader canManage={false} />);
    expect(screen.getByRole("heading", { name: "Empresas" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Nova empresa" })).not.toBeInTheDocument();
  });
});
