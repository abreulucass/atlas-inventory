import { randomUUID } from "node:crypto";

import { expect, type Page } from "@playwright/test";

export const ACCESS_COOKIE = "atlas_access";
export const REFRESH_COOKIE = "atlas_refresh";

export type TestUser = {
  name: string;
  email: string;
  password: string;
  organizationName: string;
  slug: string;
};

// Unique per call so tests never collide on the shared backend.
export function newUser(): TestUser {
  const id = `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 8)}`;
  return {
    name: `Ana ${id}`,
    email: `ana-${id}@example.com`,
    password: randomUUID(),
    organizationName: `Org ${id}`,
    slug: `org-${id}`,
  };
}

export async function fillRegistration(page: Page, user: TestUser) {
  await page.getByLabel("Nome", { exact: true }).fill(user.name);
  await page.getByLabel("E-mail").fill(user.email);
  await page.getByLabel("Senha").fill(user.password);
  await page.getByLabel("Nome da organização").fill(user.organizationName);
  await page.getByLabel("Endereço da organização").fill(user.slug);
  await page.getByRole("button", { name: "Criar conta" }).click();
}

export async function register(page: Page, user: TestUser = newUser()): Promise<TestUser> {
  await page.goto("/cadastro");
  await fillRegistration(page, user);
  await expect(page).toHaveURL("/");
  await expect(page.getByRole("heading", { name: `Olá, ${user.name}` })).toBeVisible();
  return user;
}

export async function fillLogin(page: Page, email: string, password: string) {
  await page.getByLabel("E-mail").fill(email);
  await page.getByLabel("Senha").fill(password);
  await page.getByRole("button", { name: "Entrar" }).click();
}

export async function signOut(page: Page) {
  await page.getByRole("button", { name: /Ana / }).click();
  await page.getByRole("menuitem", { name: "Sair" }).click();
  await expect(page).toHaveURL("/entrar");
}

export async function cookieValues(page: Page) {
  const cookies = await page.context().cookies();
  const value = (name: string) => cookies.find((c) => c.name === name)?.value;
  return { access: value(ACCESS_COOKIE), refresh: value(REFRESH_COOKIE) };
}
