import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

const createCompany = vi.fn();
vi.mock("@/lib/company-actions", () => ({
  createCompany: (...args: unknown[]) => createCompany(...args),
}));

import { CreateCompanyDialog } from "./create-company-dialog";

async function openDialog() {
  const user = userEvent.setup();
  render(<CreateCompanyDialog />);
  await user.click(screen.getByRole("button", { name: "Nova empresa" }));
  return user;
}

beforeEach(() => createCompany.mockReset());

describe("CreateCompanyDialog", () => {
  it("opens the form", async () => {
    await openDialog();
    expect(await screen.findByRole("dialog")).toBeInTheDocument();
    expect(screen.getByLabelText("Nome")).toBeInTheDocument();
    expect(screen.getByLabelText("CNPJ (opcional)")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Criar empresa" })).toBeInTheDocument();
  });

  it("shows field errors and stays open", async () => {
    createCompany.mockResolvedValue({
      fieldErrors: {
        name: "Já existe uma empresa com este nome.",
        cnpj: "CNPJ inválido.",
      },
      values: { name: "Acme", cnpj: "1" },
    });
    const user = await openDialog();
    await user.type(await screen.findByLabelText("Nome"), "Acme");
    await user.click(screen.getByRole("button", { name: "Criar empresa" }));
    expect(await screen.findByText("Já existe uma empresa com este nome.")).toBeInTheDocument();
    expect(screen.getByLabelText("Nome")).toHaveAccessibleDescription(
      "Já existe uma empresa com este nome.",
    );
    expect(screen.getByLabelText("CNPJ (opcional)")).toHaveAccessibleDescription("CNPJ inválido.");
    expect(screen.getByRole("dialog")).toBeInTheDocument();
  });

  it("closes when the company is created", async () => {
    createCompany.mockResolvedValue({ ok: true });
    const user = await openDialog();
    await user.type(await screen.findByLabelText("Nome"), "Acme");
    await user.click(screen.getByRole("button", { name: "Criar empresa" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  });

  it("disables submit while pending", async () => {
    let finish: (v: unknown) => void = () => {};
    createCompany.mockReturnValue(new Promise((resolve) => (finish = resolve)));
    const user = await openDialog();
    await user.type(await screen.findByLabelText("Nome"), "Acme");
    await user.click(screen.getByRole("button", { name: "Criar empresa" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Criar empresa" })).toBeDisabled());
    await act(async () => finish({ ok: true }));
  });

  it("shows the form error", async () => {
    createCompany.mockResolvedValue({ formError: "Seu papel não permite esta operação." });
    const user = await openDialog();
    await user.click(await screen.findByRole("button", { name: "Criar empresa" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Seu papel não permite esta operação.");
    expect(screen.getByRole("dialog")).toBeInTheDocument();
  });
});
