import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import type { FormState } from "@/lib/auth-actions";

const register = vi.fn<(prev: FormState, data: FormData) => Promise<FormState>>();
vi.mock("@/lib/auth-actions", () => ({
  register: (...args: [FormState, FormData]) => register(...args),
}));

import { RegisterForm } from "./register-form";

beforeEach(() => {
  register.mockReset();
});

async function fillValid(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByLabelText("Nome"), "Ana Souza");
  await user.type(screen.getByLabelText("E-mail"), "ana@example.com");
  await user.type(screen.getByLabelText("Senha"), "segredo-longo-123");
  await user.type(screen.getByLabelText("Nome da organização"), "Ação & Cia. Ltda");
}

describe("RegisterForm", () => {
  it("derives the slug from the organization name while untouched", async () => {
    const user = userEvent.setup();
    render(<RegisterForm />);
    const slug = screen.getByLabelText("Endereço da organização");

    await user.type(screen.getByLabelText("Nome da organização"), "Ação & Cia. Ltda");
    expect(slug).toHaveValue("acao-cia-ltda");

    await user.clear(screen.getByLabelText("Nome da organização"));
    await user.type(screen.getByLabelText("Nome da organização"), "a".repeat(70));
    expect(slug).toHaveValue("a".repeat(63));
  });

  it("stops deriving the slug once it is edited", async () => {
    const user = userEvent.setup();
    render(<RegisterForm />);
    const org = screen.getByLabelText("Nome da organização");
    const slug = screen.getByLabelText("Endereço da organização");

    await user.type(org, "Loja");
    await user.type(slug, "-x");
    await user.type(org, " Nova");

    expect(slug).toHaveValue("loja-x");
  });

  it("shows field errors and does not submit invalid input", async () => {
    const user = userEvent.setup();
    render(<RegisterForm />);

    await user.type(screen.getByLabelText("Senha"), "curta");
    await user.click(screen.getByRole("button", { name: "Criar conta" }));

    expect(screen.getByText("Informe seu nome, com até 120 caracteres.")).toBeInTheDocument();
    expect(screen.getByText("Informe um e-mail válido.")).toBeInTheDocument();
    expect(screen.getByText("A senha deve ter de 12 a 128 caracteres.")).toBeInTheDocument();
    expect(
      screen.getByText("Informe o nome da organização, com até 120 caracteres."),
    ).toBeInTheDocument();
    expect(screen.getByText(/^O endereço deve ter de 3 a 63 caracteres/)).toBeInTheDocument();
    expect(register).not.toHaveBeenCalled();
  });

  it("disables the button with the pending label while submitting", async () => {
    register.mockReturnValue(new Promise<FormState>(() => {}));
    const user = userEvent.setup();
    render(<RegisterForm />);

    await fillValid(user);
    await user.click(screen.getByRole("button", { name: "Criar conta" }));

    const pending = await screen.findByRole("button", { name: "Criando conta…" });
    expect(pending).toBeDisabled();
    expect(register).toHaveBeenCalledTimes(1);
  });
});
