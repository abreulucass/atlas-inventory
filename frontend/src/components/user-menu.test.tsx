import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

vi.mock("@/lib/auth-actions", () => ({ logout: vi.fn() }));

import type { Role } from "@/lib/roles";

import { UserMenu } from "./user-menu";

describe("UserMenu", () => {
  it("shows the name, e-mail and role label", async () => {
    const roles: [Role, string][] = [
      ["OWNER", "Proprietário"],
      ["ADMIN", "Administrador"],
      ["MEMBER", "Membro"],
    ];
    for (const [role, label] of roles) {
      const user = userEvent.setup();
      const view = render(<UserMenu name="Ana Souza" email="ana@example.com" role={role} />);

      await user.click(screen.getByRole("button", { name: /Ana Souza/ }));

      expect(await screen.findByText("ana@example.com")).toBeInTheDocument();
      expect(screen.getByText(label)).toBeInTheDocument();
      expect(screen.getByRole("menuitem", { name: "Sair" })).toBeInTheDocument();
      view.unmount();
    }
  });
});
