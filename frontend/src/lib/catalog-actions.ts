"use server";

import { redirect } from "next/navigation";
import { revalidatePath } from "next/cache";

import {
  accessToken,
  CHECK_DATA,
  detailOf,
  field,
  FORBIDDEN,
  ORG_ARCHIVED,
  patch,
  post,
  UNAVAILABLE,
} from "./backend";
import type { CreateState } from "./company-actions";
import { strip, validateCategory, validateProduct, validateProductEdit } from "./validation";

const CATEGORY_ARCHIVED = "Esta categoria está arquivada.";

// Backend `detail` prefix -> where the pt-BR message goes (a field name, or "form").
const DETAILS: [prefix: string, target: string, message: string][] = [
  ["Category name already in use", "name", "Já existe uma categoria com este nome."],
  ["SKU already in use", "sku", "Já existe um produto com este SKU."],
  ["Category is archived", "categoryId", CATEGORY_ARCHIVED],
  ["Category not found", "categoryId", "Categoria não encontrada."],
  [
    "Product was changed by someone else",
    "form",
    "Este produto foi alterado por outra pessoa. Recarregue a página.",
  ],
  ["Organization is archived", "form", ORG_ARCHIVED],
];

async function write(
  send: typeof post,
  path: string,
  body: unknown,
  okStatus: number,
  revalidate: string,
  values: Record<string, string>,
): Promise<CreateState> {
  const res = await send(path, body, await accessToken());
  if (!res) return { formError: UNAVAILABLE, values };
  if (res.status === 401) redirect("/auth/expired");
  if (res.status === 403) return { formError: FORBIDDEN, values };
  if (res.status === okStatus) {
    revalidatePath(revalidate);
    return { ok: true };
  }
  const detail = await detailOf(res);
  const hit = DETAILS.find(([prefix]) => detail.startsWith(prefix));
  if (hit) {
    return hit[1] === "form"
      ? { formError: hit[2], values }
      : { fieldErrors: { [hit[1]]: hit[2] }, values };
  }
  return { formError: CHECK_DATA, values };
}

// "Sem categoria" submits an empty value.
const categoryOf = (data: FormData) => field(data, "categoryId") || null;

export async function createCategory(_prev: CreateState, data: FormData): Promise<CreateState> {
  const name = field(data, "name");
  const values = { name };
  const fieldErrors = validateCategory({ name });
  if (Object.keys(fieldErrors).length > 0) return { fieldErrors, values };
  return write(post, "/api/categories", { name: strip(name) }, 201, "/categorias", values);
}

export async function createProduct(_prev: CreateState, data: FormData): Promise<CreateState> {
  const sku = field(data, "sku");
  const name = field(data, "name");
  const unit = field(data, "unit");
  const categoryId = field(data, "categoryId");
  const values = { sku, name, unit, categoryId };
  const fieldErrors = validateProduct({ sku, name, unit });
  if (Object.keys(fieldErrors).length > 0) return { fieldErrors, values };
  return write(
    post,
    "/api/products",
    { sku: strip(sku), name: strip(name), unit, categoryId: categoryOf(data) },
    201,
    "/produtos",
    values,
  );
}

export async function updateProduct(_prev: CreateState, data: FormData): Promise<CreateState> {
  const id = field(data, "id");
  const name = field(data, "name");
  const categoryId = field(data, "categoryId");
  const values = { name, categoryId };
  const fieldErrors = validateProductEdit({ name });
  if (Object.keys(fieldErrors).length > 0) return { fieldErrors, values };
  return write(
    patch,
    `/api/products/${encodeURIComponent(id)}`,
    { version: Number(field(data, "version")), name: strip(name), categoryId: categoryOf(data) },
    200,
    "/produtos",
    values,
  );
}
