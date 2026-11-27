import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

const createProduct = vi.fn();
vi.mock("@/lib/catalog-actions", () => ({
  createProduct: (...args: unknown[]) => createProduct(...args),
}));

import type { Category } from "@/lib/api";
import { formatUnit, UNITS } from "@/lib/format";
import { CreateProductDialog } from "./create-product-dialog";

const CATEGORIES: Category[] = [
  { id: "k1", name: "Bebidas", status: "ACTIVE" },
  { id: "k2", name: "Limpeza", status: "ACTIVE" },
];

async function openDialog() {
  const user = userEvent.setup();
  render(<CreateProductDialog categories={CATEGORIES} />);
  await user.click(screen.getByRole("button", { name: "Novo produto" }));
  await screen.findByRole("dialog");
  return user;
}

const optionTexts = (label: string) =>
  within(screen.getByLabelText(label)).getAllByRole("option").map((o) => o.textContent);

beforeEach(() => createProduct.mockReset());

describe("CreateProductDialog", () => {
  it("opens with SKU, Nome, Unidade, Categoria and Criar produto", async () => {
    await openDialog();
    for (const label of ["SKU", "Nome", "Unidade", "Categoria (opcional)"]) {
      expect(screen.getByLabelText(label)).toBeInTheDocument();
    }
    expect(screen.getByRole("button", { name: "Criar produto" })).toBeInTheDocument();
    expect(screen.getByLabelText("Unidade")).toHaveValue("");
  });

  it("offers the eight units and Sem categoria plus active categories", async () => {
    await openDialog();
    expect(UNITS).toHaveLength(8);
    expect(optionTexts("Unidade").slice(1)).toEqual(UNITS.map(formatUnit));
    expect(optionTexts("Categoria (opcional)")).toEqual(["Sem categoria", "Bebidas", "Limpeza"]);
  });

  it("submits the chosen values and closes on success", async () => {
    createProduct.mockResolvedValue({ ok: true });
    const user = await openDialog();
    await user.type(screen.getByLabelText("SKU"), "ab-1");
    await user.type(screen.getByLabelText("Nome"), "Água");
    await user.selectOptions(screen.getByLabelText("Unidade"), "KG");
    await user.selectOptions(screen.getByLabelText("Categoria (opcional)"), "k2");
    await user.click(screen.getByRole("button", { name: "Criar produto" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    const data = createProduct.mock.calls[0][1] as FormData;
    expect(Object.fromEntries(data)).toEqual({ sku: "ab-1", name: "Água", unit: "KG", categoryId: "k2" });
  });

  it("submits an empty categoryId for Sem categoria", async () => {
    createProduct.mockResolvedValue({ ok: true });
    const user = await openDialog();
    await user.click(screen.getByRole("button", { name: "Criar produto" }));
    await waitFor(() => expect(createProduct).toHaveBeenCalled());
    expect((createProduct.mock.calls[0][1] as FormData).get("categoryId")).toBe("");
  });

  it("disables Criar produto while pending", async () => {
    let finish: (v: unknown) => void = () => {};
    createProduct.mockReturnValue(new Promise((resolve) => (finish = resolve)));
    const user = await openDialog();
    await user.click(screen.getByRole("button", { name: "Criar produto" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Criar produto" })).toBeDisabled());
    await act(async () => finish({ ok: true }));
  });

  it("shows field errors under their field with the dialog open", async () => {
    const fieldErrors = {
      sku: "Use até 40 letras, números, ponto, hífen ou sublinhado.",
      name: "Informe um nome de até 200 caracteres.",
      unit: "Escolha uma unidade.",
      categoryId: "Esta categoria está arquivada.",
    };
    createProduct.mockResolvedValue({
      fieldErrors,
      values: { sku: "x y", name: "", unit: "", categoryId: "k1" },
    });
    const user = await openDialog();
    await user.click(screen.getByRole("button", { name: "Criar produto" }));
    const labels = { sku: "SKU", name: "Nome", unit: "Unidade", categoryId: "Categoria (opcional)" };
    for (const [key, message] of Object.entries(fieldErrors)) {
      expect(await screen.findByText(message)).toBeInTheDocument();
      expect(screen.getByLabelText(labels[key as keyof typeof labels])).toHaveAccessibleDescription(message);
    }
    expect(screen.getByLabelText("SKU")).toHaveValue("x y");
    expect(screen.getByLabelText("Categoria (opcional)")).toHaveValue("k1");
    expect(screen.getByRole("dialog")).toBeInTheDocument();
  });

  it("shows the form error in an alert", async () => {
    createProduct.mockResolvedValue({ formError: "Seu papel não permite esta operação." });
    const user = await openDialog();
    await user.click(screen.getByRole("button", { name: "Criar produto" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Seu papel não permite esta operação.");
    expect(screen.getByRole("dialog")).toBeInTheDocument();
  });
});
