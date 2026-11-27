"use server";

import { cookies } from "next/headers";
import { redirect } from "next/navigation";

import { backendFetch, field, post, UNAVAILABLE } from "./backend";
import { safeNext } from "./safe-next";
import {
  ACCESS_COOKIE,
  REFRESH_COOKIE,
  sessionCookies,
  type TokenPair,
} from "./session";
import {
  normalizeEmail,
  strip,
  validateLogin,
  validateRegister,
  type RegisterInput,
} from "./validation";

// Never carries a token (SES-02).
export type FormState = {
  fieldErrors?: Partial<Record<string, string>>;
  formError?: string;
  values?: Record<string, string>;
};

const INVALID_CREDENTIALS = "E-mail ou senha inválidos.";
const CHECK_DATA = "Verifique os dados informados.";
const DUPLICATE_EMAIL = "Este e-mail já está cadastrado.";
const NOT_A_MEMBER = "Você não faz mais parte desta organização.";
const DUPLICATE_SLUG = "Este endereço já está em uso.";

async function startSession(res: Response) {
  const jar = await cookies();
  for (const c of sessionCookies((await res.json()) as TokenPair)) {
    jar.set(c.name, c.value, c.options);
  }
}

export async function login(_prev: FormState, data: FormData): Promise<FormState> {
  const email = field(data, "email");
  const password = field(data, "password");
  const values = { email };
  const fieldErrors = validateLogin({ email, password });
  if (Object.keys(fieldErrors).length > 0) return { fieldErrors, values };

  const res = await post("/api/auth/login", { email: normalizeEmail(email), password });
  if (!res) return { formError: UNAVAILABLE, values };
  if (res.status === 401) return { formError: INVALID_CREDENTIALS, values };
  if (!res.ok) return { formError: CHECK_DATA, values };
  await startSession(res);
  redirect(safeNext(field(data, "next")));
}

export async function register(_prev: FormState, data: FormData): Promise<FormState> {
  const input: RegisterInput = {
    name: field(data, "name"),
    email: field(data, "email"),
    password: field(data, "password"),
    organizationName: field(data, "organizationName"),
    organizationSlug: field(data, "organizationSlug"),
  };
  const { password, ...values } = input;
  const fieldErrors = validateRegister(input);
  if (Object.keys(fieldErrors).length > 0) return { fieldErrors, values };

  const res = await post("/api/auth/register", {
    name: strip(input.name),
    email: normalizeEmail(input.email),
    password,
    organizationName: strip(input.organizationName),
    organizationSlug: input.organizationSlug,
  });
  if (!res) return { formError: UNAVAILABLE, values };
  if (res.status === 409) {
    const detail = String(((await res.json().catch(() => ({}))) as { detail?: unknown }).detail ?? "");
    if (detail.startsWith("e-mail already registered")) {
      return { fieldErrors: { email: DUPLICATE_EMAIL }, values };
    }
    if (detail.startsWith("Slug already in use")) {
      return { fieldErrors: { organizationSlug: DUPLICATE_SLUG }, values };
    }
  }
  if (res.status !== 201) return { formError: CHECK_DATA, values };
  await startSession(res);
  redirect("/");
}

export async function logout(): Promise<void> {
  const jar = await cookies();
  const refreshToken = jar.get(REFRESH_COOKIE)?.value;
  try {
    if (refreshToken) {
      await backendFetch("/api/auth/logout", {
        method: "POST",
        body: JSON.stringify({ refreshToken }),
      });
    }
  } catch {
    // Signed out locally anyway (LOG-06).
  } finally {
    for (const name of [ACCESS_COOKIE, REFRESH_COOKIE]) {
      jar.set(name, "", { path: "/", maxAge: 0 });
    }
  }
  redirect("/entrar");
}

// Never carries a token (SWITCH-07).
export type SwitchState = { error: string };

export async function switchOrganization(organizationId: string): Promise<SwitchState> {
  const token = (await cookies()).get(ACCESS_COOKIE)?.value;
  if (!token) redirect("/auth/expired");
  const res = await post("/api/auth/switch-organization", { organizationId }, token);
  if (!res) return { error: UNAVAILABLE };
  if (res.status === 401) redirect("/auth/expired");
  if (res.status === 403) return { error: NOT_A_MEMBER };
  if (!res.ok) return { error: UNAVAILABLE };
  await startSession(res);
  redirect("/");
}
