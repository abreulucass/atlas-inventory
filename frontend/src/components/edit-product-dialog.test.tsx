import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

const updateProduct = vi.fn();
vi.mock("@/lib/catalog-actions", () => ({
  updateProduct: (...args: unknown[]) => updateProduct(...args),
}));

import type { Category, Product } from "@/lib/api";
import { EditProductDialog } from "./edit-product-dialog";

const ACTIVE: Category[] = [
  { id: "k1", name: "Bebidas", status: "ACTIVE" },
  { id: "k2", name: "Limpeza", status: "ACTIVE" },
];
const ARCHIVED: Category = { id: "k9", name: "Antiga", status: "ARCHIVED" };
const PRODUCT: Product = {
  id: "p1",
  sku: "AB-1",
  name: "Água",
  unit: "KG",
  fractional: true,
  categoryId: "k1",
  status: "ACTIVE",
  version: 3,
};

async function openDialog(product = PRODUCT, currentCategory?: Category) {
  const user = userEvent.setup();
  render(<EditProductDialog product={product} categories={ACTIVE} currentCategory={currentCategory} />);
  await user.click(screen.getByRole("button", { name: "Editar" }));
  await screen.findByRole("dialog", { name: "Editar produto" });
  return user;
}

beforeEach(() => updateProduct.mockReset());

describe("EditProductDialog", () => {
  it("shows SKU and unit read-only and prefilled Nome and Categoria", async () => {
    await openDialog();
    const dialog = screen.getByRole("dialog");
    expect(dialog).toHaveTextContent("AB-1");
    expect(dialog).toHaveTextContent("Quilograma (KG)");
    expect(screen.queryByLabelText("SKU")).not.toBeInTheDocument();
    expect(screen.getByLabelText("Nome")).toHaveValue("Água");
    expect(screen.getByLabelText("Categoria (opcional)")).toHaveValue("k1");
    expect(screen.getByRole("button", { name: "Salvar" })).toBeInTheDocument();
  });

  it("preselects Sem categoria for a product with no category", async () => {
    await openDialog({ ...PRODUCT, categoryId: null });
    expect(screen.getByLabelText("Categoria (opcional)")).toHaveValue("");
  });

  it("keeps an archived current category selected", async () => {
    updateProduct.mockResolvedValue({ ok: true });
    const user = await openDialog({ ...PRODUCT, categoryId: "k9" }, ARCHIVED);
    expect(screen.getByLabelText("Categoria (opcional)")).toHaveValue("k9");
    await user.click(screen.getByRole("button", { name: "Salvar" }));
    await waitFor(() => expect(updateProduct).toHaveBeenCalled());
    const data = updateProduct.mock.calls[0][1] as FormData;
    expect(data.get("categoryId")).toBe("k9");
    expect(data.get("id")).toBe("p1");
    expect(data.get("version")).toBe("3");
  });

  it("offers an archived category only when it is the current one", async () => {
    await openDialog();
    expect(screen.queryByRole("option", { name: "Antiga" })).not.toBeInTheDocument();
  });

  it("closes on success", async () => {
    updateProduct.mockResolvedValue({ ok: true });
    const user = await openDialog();
    await user.clear(screen.getByLabelText("Nome"));
    await user.type(screen.getByLabelText("Nome"), "Água mineral");
    await user.selectOptions(screen.getByLabelText("Categoria (opcional)"), "k2");
    await user.click(screen.getByRole("button", { name: "Salvar" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    const data = updateProduct.mock.calls[0][1] as FormData;
    expect(Object.fromEntries(data)).toEqual({
      id: "p1",
      version: "3",
      name: "Água mineral",
      categoryId: "k2",
    });
  });

  it("disables Salvar while pending and shows the form error", async () => {
    let finish: (v: unknown) => void = () => {};
    updateProduct.mockReturnValue(new Promise((resolve) => (finish = resolve)));
    const user = await openDialog();
    await user.click(screen.getByRole("button", { name: "Salvar" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Salvar" })).toBeDisabled());
    await act(async () =>
      finish({
        formError: "Este produto foi alterado por outra pessoa. Recarregue a página.",
        values: { name: "Água", categoryId: "k1" },
      }),
    );
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Este produto foi alterado por outra pessoa. Recarregue a página.",
    );
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Salvar" })).toBeEnabled();
  });

  it("shows field errors under their field and keeps the typed values", async () => {
    updateProduct.mockResolvedValue({
      fieldErrors: {
        name: "Informe um nome de até 200 caracteres.",
        categoryId: "Esta categoria está arquivada.",
      },
      values: { name: "", categoryId: "k2" },
    });
    const user = await openDialog();
    await user.click(screen.getByRole("button", { name: "Salvar" }));
    expect(await screen.findByLabelText("Nome")).toHaveAccessibleDescription(
      "Informe um nome de até 200 caracteres.",
    );
    expect(screen.getByLabelText("Categoria (opcional)")).toHaveAccessibleDescription(
      "Esta categoria está arquivada.",
    );
    expect(screen.getByLabelText("Categoria (opcional)")).toHaveValue("k2");
  });
});
