import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

const createCategory = vi.fn();
vi.mock("@/lib/catalog-actions", () => ({
  createCategory: (...args: unknown[]) => createCategory(...args),
}));

import { CreateCategoryDialog } from "./create-category-dialog";

async function openDialog() {
  const user = userEvent.setup();
  render(<CreateCategoryDialog />);
  await user.click(screen.getByRole("button", { name: "Nova categoria" }));
  return user;
}

beforeEach(() => createCategory.mockReset());

describe("CreateCategoryDialog", () => {
  it("opens with Nome and Criar categoria", async () => {
    await openDialog();
    expect(await screen.findByRole("dialog")).toBeInTheDocument();
    expect(screen.getByLabelText("Nome")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Criar categoria" })).toBeInTheDocument();
  });

  it("closes on success", async () => {
    createCategory.mockResolvedValue({ ok: true });
    const user = await openDialog();
    await user.type(await screen.findByLabelText("Nome"), "Bebidas");
    await user.click(screen.getByRole("button", { name: "Criar categoria" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(createCategory.mock.calls[0][1].get("name")).toBe("Bebidas");
  });

  it("disables Criar categoria while pending", async () => {
    let finish: (v: unknown) => void = () => {};
    createCategory.mockReturnValue(new Promise((resolve) => (finish = resolve)));
    const user = await openDialog();
    await user.type(await screen.findByLabelText("Nome"), "Bebidas");
    await user.click(screen.getByRole("button", { name: "Criar categoria" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Criar categoria" })).toBeDisabled());
    await act(async () => finish({ ok: true }));
  });

  it("shows the field error under Nome and keeps the dialog open", async () => {
    createCategory.mockResolvedValue({
      fieldErrors: { name: "Já existe uma categoria com este nome." },
      values: { name: "Bebidas" },
    });
    const user = await openDialog();
    await user.type(await screen.findByLabelText("Nome"), "Bebidas");
    await user.click(screen.getByRole("button", { name: "Criar categoria" }));
    expect(await screen.findByText("Já existe uma categoria com este nome.")).toBeInTheDocument();
    expect(screen.getByLabelText("Nome")).toHaveAccessibleDescription("Já existe uma categoria com este nome.");
    expect(screen.getByLabelText("Nome")).toHaveValue("Bebidas");
    expect(screen.getByRole("dialog")).toBeInTheDocument();
  });

  it("shows the form error in an alert and keeps the dialog open", async () => {
    createCategory.mockResolvedValue({ formError: "Seu papel não permite esta operação." });
    const user = await openDialog();
    await user.type(await screen.findByLabelText("Nome"), "Bebidas");
    await user.click(screen.getByRole("button", { name: "Criar categoria" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Seu papel não permite esta operação.");
    expect(screen.getByRole("dialog")).toBeInTheDocument();
  });
});
