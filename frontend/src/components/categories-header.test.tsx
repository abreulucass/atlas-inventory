import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

vi.mock("@/components/create-category-dialog", () => ({
  CreateCategoryDialog: () => <button>Nova categoria</button>,
}));

import { canManage } from "@/lib/roles";

import { CategoriesHeader } from "./categories-header";

describe("CategoriesHeader", () => {
  it("shows Nova categoria for OWNER and ADMIN", () => {
    for (const role of ["OWNER", "ADMIN"] as const) {
      const { unmount } = render(<CategoriesHeader canManage={canManage(role)} />);
      expect(screen.getByRole("heading", { name: "Categorias" })).toBeInTheDocument();
      expect(screen.getByRole("button", { name: "Nova categoria" })).toBeInTheDocument();
      unmount();
    }
  });

  it("hides Nova categoria for MEMBER", () => {
    render(<CategoriesHeader canManage={canManage("MEMBER")} />);
    expect(screen.getByRole("heading", { name: "Categorias" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Nova categoria" })).not.toBeInTheDocument();
  });
});
