import { act, cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

const createProduct = vi.fn();
vi.mock("@/lib/catalog-actions", () => ({
  createProduct: (...args: unknown[]) => createProduct(...args),
}));

import type { Category } from "@/lib/api";
import { formatUnit, UNITS } from "@/lib/format";
import { CreateProductDialog } from "./create-product-dialog";
import { ProductsHeader } from "./products-header";

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

async function fillValid(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByLabelText("SKU"), "ab-1");
  await user.type(screen.getByLabelText("Nome"), "Água");
  await user.selectOptions(screen.getByLabelText("Unidade"), "KG");
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
    const options = (label: string) =>
      within(screen.getByLabelText(label))
        .getAllByRole("option")
        .map((o) => [(o as HTMLOptionElement).value, o.textContent]);
    expect(options("Unidade").slice(1)).toEqual(UNITS.map((unit) => [unit, formatUnit(unit)]));
    expect(options("Unidade").slice(1)).toEqual([
      ["UN", "Unidade (UN)"],
      ["CX", "Caixa (CX)"],
      ["PC", "Peça (PC)"],
      ["KG", "Quilograma (KG)"],
      ["G", "Grama (G)"],
      ["L", "Litro (L)"],
      ["ML", "Mililitro (ML)"],
      ["M", "Metro (M)"],
    ]);
    expect(options("Categoria (opcional)")).toEqual([
      ["", "Sem categoria"],
      ["k1", "Bebidas"],
      ["k2", "Limpeza"],
    ]);

    // Given every category, as the products page does, an archived one is never offered.
    cleanup();
    const user = userEvent.setup();
    render(
      <ProductsHeader
        canManage
        categories={[...CATEGORIES, { id: "k3", name: "Antiga", status: "ARCHIVED" }]}
      />,
    );
    await user.click(screen.getByRole("button", { name: "Novo produto" }));
    await screen.findByRole("dialog");
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
    await fillValid(user);
    await user.click(screen.getByRole("button", { name: "Criar produto" }));
    await waitFor(() => expect(createProduct).toHaveBeenCalled());
    expect((createProduct.mock.calls[0][1] as FormData).get("categoryId")).toBe("");
  });

  it("disables Criar produto while pending", async () => {
    let finish: (v: unknown) => void = () => {};
    createProduct.mockReturnValue(new Promise((resolve) => (finish = resolve)));
    const user = await openDialog();
    await fillValid(user);
    await user.click(screen.getByRole("button", { name: "Criar produto" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Criar produto" })).toBeDisabled());
    // A server error then lands under its field and leaves the dialog open.
    const message = "Já existe um produto com este SKU.";
    await act(async () => finish({ fieldErrors: { sku: message }, values: { sku: "ab-1", name: "Água", unit: "KG", categoryId: "" } }));
    expect(await screen.findByText(message)).toBeInTheDocument();
    expect(screen.getByLabelText("SKU")).toHaveAccessibleDescription(message);
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Criar produto" })).toBeEnabled();
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
    await fillValid(user);
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

  it("validates on the client and sends nothing for a bad value", async () => {
    const user = await openDialog();
    await user.type(screen.getByLabelText("SKU"), " a b ");
    await user.type(screen.getByLabelText("Nome"), "x".repeat(201));
    await user.click(screen.getByRole("button", { name: "Criar produto" }));
    expect(await screen.findByText("Use até 40 letras, números, ponto, hífen ou sublinhado.")).toBeInTheDocument();
    expect(screen.getByText("Informe um nome de até 200 caracteres.")).toBeInTheDocument();
    expect(screen.getByText("Escolha uma unidade.")).toBeInTheDocument();
    expect(screen.getByLabelText("SKU")).toHaveValue(" a b ");
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    expect(createProduct).not.toHaveBeenCalled();
  });

  it("refuses a forged unit without calling the action", async () => {
    const user = await openDialog();
    await user.type(screen.getByLabelText("SKU"), "ab-1");
    await user.type(screen.getByLabelText("Nome"), "Água");
    const forged = document.createElement("option");
    forged.value = "BOX";
    forged.textContent = "Caixa";
    screen.getByLabelText("Unidade").append(forged);
    await user.selectOptions(screen.getByLabelText("Unidade"), "BOX");
    await user.click(screen.getByRole("button", { name: "Criar produto" }));
    expect(await screen.findByText("Escolha uma unidade.")).toBeInTheDocument();
    expect(createProduct).not.toHaveBeenCalled();
  });

  it("sends a SKU of 40 and a name of 200 characters despite the padding", async () => {
    createProduct.mockResolvedValue({ ok: true });
    const user = await openDialog();
    await user.type(screen.getByLabelText("SKU"), `  ${"s".repeat(40)}  `);
    await user.type(screen.getByLabelText("Nome"), ` ${"n".repeat(200)} `);
    await user.selectOptions(screen.getByLabelText("Unidade"), "L");
    await user.click(screen.getByRole("button", { name: "Criar produto" }));
    await waitFor(() => expect(createProduct).toHaveBeenCalledTimes(1));
  });

  it("sends a one-character SKU and name", async () => {
    createProduct.mockResolvedValue({ ok: true });
    const user = await openDialog();
    await user.type(screen.getByLabelText("SKU"), "a");
    await user.type(screen.getByLabelText("Nome"), "n");
    await user.selectOptions(screen.getByLabelText("Unidade"), "UN");
    await user.click(screen.getByRole("button", { name: "Criar produto" }));
    await waitFor(() => expect(createProduct).toHaveBeenCalledTimes(1));
  });

  it("shows the form error in an alert", async () => {
    createProduct.mockResolvedValue({ formError: "Seu papel não permite esta operação." });
    const user = await openDialog();
    await fillValid(user);
    await user.click(screen.getByRole("button", { name: "Criar produto" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Seu papel não permite esta operação.");
    expect(screen.getByRole("dialog")).toBeInTheDocument();
  });
});
