import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

const createWarehouse = vi.fn();
vi.mock("@/lib/company-actions", () => ({
  createWarehouse: (...args: unknown[]) => createWarehouse(...args),
}));

import { CreateWarehouseDialog } from "./create-warehouse-dialog";

async function openDialog() {
  const user = userEvent.setup();
  render(<CreateWarehouseDialog companyId="c1" />);
  await user.click(screen.getByRole("button", { name: "Novo depósito" }));
  return user;
}

beforeEach(() => createWarehouse.mockReset());

describe("CreateWarehouseDialog", () => {
  it("opens the form", async () => {
    await openDialog();
    expect(await screen.findByRole("dialog")).toBeInTheDocument();
    expect(screen.getByLabelText("Código")).toBeInTheDocument();
    expect(screen.getByLabelText("Nome")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Criar depósito" })).toBeInTheDocument();
  });

  it("shows field errors and stays open", async () => {
    createWarehouse.mockResolvedValue({
      fieldErrors: {
        code: "Já existe um depósito com este código nesta empresa.",
        name: "Já existe um depósito com este nome nesta empresa.",
      },
      values: { code: "CD-1", name: "Central" },
    });
    const user = await openDialog();
    await user.type(await screen.findByLabelText("Código"), "CD-1");
    await user.click(screen.getByRole("button", { name: "Criar depósito" }));
    expect(
      await screen.findByText("Já existe um depósito com este código nesta empresa."),
    ).toBeInTheDocument();
    expect(screen.getByLabelText("Código")).toHaveAccessibleDescription(
      "Já existe um depósito com este código nesta empresa.",
    );
    expect(screen.getByLabelText("Nome")).toHaveAccessibleDescription(
      "Já existe um depósito com este nome nesta empresa.",
    );
    expect(screen.getByRole("dialog")).toBeInTheDocument();
  });

  it("closes when the warehouse is created", async () => {
    createWarehouse.mockResolvedValue({ ok: true });
    const user = await openDialog();
    await user.type(await screen.findByLabelText("Código"), "CD-1");
    await user.click(screen.getByRole("button", { name: "Criar depósito" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  });

  it("disables submit while pending", async () => {
    let finish: (v: unknown) => void = () => {};
    createWarehouse.mockReturnValue(new Promise((resolve) => (finish = resolve)));
    const user = await openDialog();
    await user.type(await screen.findByLabelText("Código"), "CD-1");
    await user.click(screen.getByRole("button", { name: "Criar depósito" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Criar depósito" })).toBeDisabled());
    await act(async () => finish({ ok: true }));
  });

  it("shows the form error", async () => {
    createWarehouse.mockResolvedValue({ formError: "Seu papel não permite esta operação." });
    const user = await openDialog();
    await user.click(await screen.findByRole("button", { name: "Criar depósito" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Seu papel não permite esta operação.");
    expect(screen.getByRole("dialog")).toBeInTheDocument();
  });

  it("sends the company id", async () => {
    createWarehouse.mockResolvedValue({ ok: true });
    const user = await openDialog();
    await user.click(await screen.findByRole("button", { name: "Criar depósito" }));
    await waitFor(() => expect(createWarehouse).toHaveBeenCalled());
    expect((createWarehouse.mock.calls[0][1] as FormData).get("companyId")).toBe("c1");
  });
});
