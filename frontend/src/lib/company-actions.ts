"use server";

import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { revalidatePath } from "next/cache";

import { field, post, UNAVAILABLE } from "./backend";
import { ACCESS_COOKIE } from "./session";
import { strip, validateCompany, validateWarehouse } from "./validation";

// Never carries a token (SEC-02).
export type CreateState = {
  ok?: true;
  fieldErrors?: Partial<Record<string, string>>;
  formError?: string;
  values?: Record<string, string>;
};

const FORBIDDEN = "Seu papel não permite esta operação.";
const CHECK_DATA = "Verifique os dados informados.";
const ORG_ARCHIVED = "Esta organização está arquivada.";
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

async function accessToken(): Promise<string> {
  const token = (await cookies()).get(ACCESS_COOKIE)?.value;
  if (!token) redirect("/auth/expired");
  return token;
}

async function detailOf(res: Response): Promise<string> {
  const body = (await res.json().catch(() => ({}))) as { detail?: unknown };
  return String(body.detail ?? "");
}

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
