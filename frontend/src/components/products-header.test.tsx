import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

vi.mock("@/components/create-product-dialog", () => ({
  CreateProductDialog: () => <button>Novo produto</button>,
}));

import { canManage } from "@/lib/roles";

import { ProductsHeader } from "./products-header";

describe("ProductsHeader", () => {
  it("shows Novo produto for OWNER and ADMIN", () => {
    for (const role of ["OWNER", "ADMIN"] as const) {
      const { unmount } = render(<ProductsHeader canManage={canManage(role)} categories={[]} />);
      expect(screen.getByRole("heading", { name: "Produtos" })).toBeInTheDocument();
      expect(screen.getByRole("button", { name: "Novo produto" })).toBeInTheDocument();
      unmount();
    }
  });

  it("hides Novo produto for MEMBER", () => {
    render(<ProductsHeader canManage={canManage("MEMBER")} categories={[]} />);
    expect(screen.getByRole("heading", { name: "Produtos" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Novo produto" })).not.toBeInTheDocument();
  });
});
