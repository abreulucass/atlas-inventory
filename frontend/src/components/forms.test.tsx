import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import type { FormState } from "@/lib/auth-actions";

const login = vi.fn<(prev: FormState, data: FormData) => Promise<FormState>>();
vi.mock("@/lib/auth-actions", () => ({
  login: (...args: [FormState, FormData]) => login(...args),
}));

import { LoginForm } from "./login-form";

beforeEach(() => {
  login.mockReset();
});

describe("forms", () => {
  it("tie labels and announce errors on every field", async () => {
    const user = userEvent.setup();
    render(<LoginForm />);

    await user.click(screen.getByRole("button", { name: "Entrar" }));

    for (const label of ["E-mail", "Senha"]) {
      const input = screen.getByLabelText(label);
      expect(input).toHaveAttribute("aria-invalid", "true");
      const message = document.getElementById(input.getAttribute("aria-describedby")!);
      expect(message).toHaveTextContent(/^Informe /);
    }
  });

  it("submit a single request on double click", async () => {
    login.mockReturnValue(new Promise<FormState>(() => {}));
    const user = userEvent.setup();
    render(<LoginForm />);

    await user.type(screen.getByLabelText("E-mail"), "ana@example.com");
    await user.type(screen.getByLabelText("Senha"), "segredo-longo-123");
    await user.dblClick(screen.getByRole("button", { name: "Entrar" }));

    expect(login).toHaveBeenCalledTimes(1);
  });
});
