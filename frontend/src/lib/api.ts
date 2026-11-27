import "server-only";

import { cookies } from "next/headers";
import { redirect } from "next/navigation";
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
async function authedGet<T>(path: string): Promise<T> {
  const jar = await cookies();
  const token = jar.get(ACCESS_COOKIE)?.value;
  if (!token) {
    // A refresh cookie without access means the proxy could not refresh: unavailable, keep the session (SES-07).
    if (jar.get(REFRESH_COOKIE)) throw new ServiceUnavailableError();
    redirect("/auth/expired");
  }
  const res = await backendFetch(path, { token });
  if (res.status === 401) redirect("/auth/expired");
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
