import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import type { FormState } from "@/lib/auth-actions";

const login = vi.fn<(prev: FormState, data: FormData) => Promise<FormState>>();
const register = vi.fn<(prev: FormState, data: FormData) => Promise<FormState>>();
vi.mock("@/lib/auth-actions", () => ({
  login: (...args: [FormState, FormData]) => login(...args),
  register: (...args: [FormState, FormData]) => register(...args),
}));

import { LoginForm } from "./login-form";
import { RegisterForm } from "./register-form";

beforeEach(() => {
  login.mockReset();
  register.mockReset();
});

type User = ReturnType<typeof userEvent.setup>;

function expectAnnounced(labels: string[]) {
  for (const label of labels) {
    const input = screen.getByLabelText(label);
    expect(input).toHaveAttribute("aria-invalid", "true");
    const message = document.getElementById(input.getAttribute("aria-describedby")!);
    expect(message).toHaveTextContent(/^(Informe|A senha|O endereço)/);
  }
}

async function fillLogin(user: User) {
  await user.type(screen.getByLabelText("E-mail"), "ana@example.com");
  await user.type(screen.getByLabelText("Senha"), "segredo-longo-123");
}

async function fillRegister(user: User) {
  await user.type(screen.getByLabelText("Nome"), "Ana Souza");
  await fillLogin(user);
  await user.type(screen.getByLabelText("Nome da organização"), "Loja Ana");
}

describe("forms", () => {
  it("tie labels and announce errors on every field", async () => {
    const user = userEvent.setup();
    const login = render(<LoginForm />);
    await user.click(screen.getByRole("button", { name: "Entrar" }));
    expectAnnounced(["E-mail", "Senha"]);
    login.unmount();

    render(<RegisterForm />);
    await user.click(screen.getByRole("button", { name: "Criar conta" }));
    expectAnnounced([
      "Nome",
      "E-mail",
      "Senha",
      "Nome da organização",
      "Endereço da organização",
    ]);
    expect(screen.getByLabelText("Nome")).toHaveAccessibleDescription(
      "Informe seu nome, com até 120 caracteres.",
    );
  });

  it("submit a single request on double click", async () => {
    login.mockReturnValue(new Promise<FormState>(() => {}));
    register.mockReturnValue(new Promise<FormState>(() => {}));
    const user = userEvent.setup();

    const loginView = render(<LoginForm />);
    await fillLogin(user);
    await user.dblClick(screen.getByRole("button", { name: "Entrar" }));
    expect(login).toHaveBeenCalledTimes(1);
    loginView.unmount();

    render(<RegisterForm />);
    await fillRegister(user);
    await user.dblClick(screen.getByRole("button", { name: "Criar conta" }));
    expect(register).toHaveBeenCalledTimes(1);
  });
});
