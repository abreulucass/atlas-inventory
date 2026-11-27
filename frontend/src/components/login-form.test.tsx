import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import type { FormState } from "@/lib/auth-actions";

const login = vi.fn<(prev: FormState, data: FormData) => Promise<FormState>>();
vi.mock("@/lib/auth-actions", () => ({ login: (...args: [FormState, FormData]) => login(...args) }));

import { LoginForm } from "./login-form";

beforeEach(() => {
  login.mockReset();
});

describe("LoginForm", () => {
  it("disables the button with the pending label while submitting", async () => {
    let finish!: (state: FormState) => void;
    login.mockReturnValue(new Promise<FormState>((resolve) => (finish = resolve)));
    const user = userEvent.setup();
    render(<LoginForm />);

    await user.type(screen.getByLabelText("E-mail"), "ana@example.com");
    await user.type(screen.getByLabelText("Senha"), "segredo-longo-123");
    await user.click(screen.getByRole("button", { name: "Entrar" }));

    const pending = await screen.findByRole("button", { name: "Entrando…" });
    expect(pending).toBeDisabled();

    finish({ formError: "E-mail ou senha inválidos." });
    expect(await screen.findByRole("alert")).toHaveTextContent("E-mail ou senha inválidos.");
    expect(screen.getByRole("button", { name: "Entrar" })).toBeEnabled();
  });

  it("keeps the e-mail and clears the password after a failed login", async () => {
    login.mockResolvedValue({ formError: "E-mail ou senha inválidos.", values: { email: "ana@example.com" } });
    const user = userEvent.setup();
    render(<LoginForm next="/estoque" />);

    await user.type(screen.getByLabelText("E-mail"), "ana@example.com");
    await user.type(screen.getByLabelText("Senha"), "segredo-longo-123");
    await user.click(screen.getByRole("button", { name: "Entrar" }));

    expect(await screen.findByRole("alert")).toBeInTheDocument();
    expect(screen.getByLabelText("E-mail")).toHaveValue("ana@example.com");
    expect(screen.getByLabelText("Senha")).toHaveValue("");
    expect(login.mock.calls[0][1].get("next")).toBe("/estoque");
  });
});
