import "server-only";

import { cookies } from "next/headers";
import { notFound, redirect } from "next/navigation";
import { cache } from "react";

import { backendFetch, ServiceUnavailableError } from "./backend";
import type { Role } from "./roles";
import { ACCESS_COOKIE, REFRESH_COOKIE } from "./session";

export type Me = {
  id: string;
  name: string;
  email: string;
  organizationId: string;
  role: Role;
};

// RSC cannot clear cookies, so a rejected token goes through /auth/expired.
// SPEC_DEVIATION: no `next` here; an RSC does not know the request path. The route sends the user to /entrar.
async function authedGet<T>(path: string, opts: { notFoundOn404?: boolean } = {}): Promise<T> {
  const jar = await cookies();
  const token = jar.get(ACCESS_COOKIE)?.value;
  if (!token) {
    // A refresh cookie without access means the proxy could not refresh: unavailable, keep the session (SES-07).
    if (jar.get(REFRESH_COOKIE)) throw new ServiceUnavailableError();
    redirect("/auth/expired");
  }
  const res = await backendFetch(path, { token });
  if (res.status === 401) redirect("/auth/expired");
  if (res.status === 404 && opts.notFoundOn404) notFound();
  if (!res.ok) throw new Error(`GET ${path} failed with ${res.status}`);
  return (await res.json()) as T;
}

export const getMe = cache(() => authedGet<Me>("/api/me"));

export type Organization = {
  id: string;
  name: string;
  slug: string;
  role: Role;
  status: "ACTIVE" | "ARCHIVED";
};

export const getOrganizations = cache(() => authedGet<Organization[]>("/api/me/organizations"));

export type Company = {
  id: string;
  name: string;
  cnpj: string | null;
  status: "ACTIVE" | "ARCHIVED";
};

export type Warehouse = {
  id: string;
  companyId: string;
  name: string;
  code: string;
  status: "ACTIVE" | "ARCHIVED";
};

const archivedQuery = (includeArchived: boolean) => (includeArchived ? "?includeArchived=true" : "");

export const getCompanies = (includeArchived = false) =>
  authedGet<Company[]>(`/api/companies${archivedQuery(includeArchived)}`);

export const getCompany = (id: string) =>
  authedGet<Company>(`/api/companies/${encodeURIComponent(id)}`, { notFoundOn404: true });

export const getWarehouses = (companyId: string, includeArchived = false) =>
  authedGet<Warehouse[]>(
    `/api/companies/${encodeURIComponent(companyId)}/warehouses${archivedQuery(includeArchived)}`,
    { notFoundOn404: true },
  );
