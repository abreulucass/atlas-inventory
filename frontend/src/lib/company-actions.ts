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
  post,
  UNAVAILABLE,
} from "./backend";
import { strip, validateCompany, validateWarehouse } from "./validation";

// Never carries a token (SEC-02).
export type CreateState = {
  ok?: true;
  fieldErrors?: Partial<Record<string, string>>;
  formError?: string;
  values?: Record<string, string>;
};

const COMPANY_ARCHIVED = "Esta empresa está arquivada.";

// Backend `detail` prefix -> where the pt-BR message goes (a field name, or "form").
const DETAILS: [prefix: string, target: string, message: string][] = [
  ["Company name already in use", "name", "Já existe uma empresa com este nome."],
  ["CNPJ already in use", "cnpj", "Já existe uma empresa com este CNPJ."],
  ["Invalid CNPJ", "cnpj", "CNPJ inválido."],
  ["Warehouse code already in use", "code", "Já existe um depósito com este código nesta empresa."],
  ["Warehouse name already in use", "name", "Já existe um depósito com este nome nesta empresa."],
  ["Company is archived", "form", COMPANY_ARCHIVED],
  ["Organization is archived", "form", ORG_ARCHIVED],
];

// Never carries a token (SEC-02).
export type ArchiveState = { ok: true } | { error: string };

async function create(
  path: string,
  body: unknown,
  paths: string[],
  values: Record<string, string>,
): Promise<CreateState> {
  const res = await post(path, body, await accessToken());
  if (!res) return { formError: UNAVAILABLE, values };
  if (res.status === 401) redirect("/auth/expired");
  if (res.status === 403) return { formError: FORBIDDEN, values };
  if (res.status === 201) {
    for (const p of paths) revalidatePath(p);
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

export async function createCompany(_prev: CreateState, data: FormData): Promise<CreateState> {
  const name = field(data, "name");
  const cnpj = field(data, "cnpj");
  const values = { name, cnpj };
  const fieldErrors = validateCompany({ name });
  if (Object.keys(fieldErrors).length > 0) return { fieldErrors, values };
  const trimmedCnpj = strip(cnpj);
  return create(
    "/api/companies",
    { name: strip(name), ...(trimmedCnpj ? { cnpj: trimmedCnpj } : {}) },
    ["/empresas"],
    values,
  );
}

export async function createWarehouse(_prev: CreateState, data: FormData): Promise<CreateState> {
  const companyId = field(data, "companyId");
  const code = field(data, "code");
  const name = field(data, "name");
  const values = { code, name };
  const fieldErrors = validateWarehouse({ code, name });
  if (Object.keys(fieldErrors).length > 0) return { fieldErrors, values };
  return create(
    `/api/companies/${encodeURIComponent(companyId)}/warehouses`,
    { code: strip(code), name: strip(name) },
    [`/empresas/${companyId}`],
    values,
  );
}

async function lifecycle(
  path: string,
  action: "archive" | "unarchive",
  companyId: string,
): Promise<ArchiveState> {
  const res = await post(`${path}/${action}`, undefined, await accessToken());
  if (!res) return { error: UNAVAILABLE };
  if (res.status === 401) redirect("/auth/expired");
  if (res.status === 403) return { error: FORBIDDEN };
  if (res.status === 204) {
    revalidatePath("/empresas");
    revalidatePath(`/empresas/${companyId}`);
    return { ok: true };
  }
  const detail = await detailOf(res);
  if (detail.startsWith("Organization is archived")) return { error: ORG_ARCHIVED };
  return { error: CHECK_DATA };
}

const company = (id: string) => `/api/companies/${encodeURIComponent(id)}`;
const warehouse = (companyId: string, id: string) =>
  `${company(companyId)}/warehouses/${encodeURIComponent(id)}`;

export const archiveCompany = async (id: string) => lifecycle(company(id), "archive", id);
export const unarchiveCompany = async (id: string) => lifecycle(company(id), "unarchive", id);
export const archiveWarehouse = async (companyId: string, id: string) =>
  lifecycle(warehouse(companyId, id), "archive", companyId);
export const unarchiveWarehouse = async (companyId: string, id: string) =>
  lifecycle(warehouse(companyId, id), "unarchive", companyId);
