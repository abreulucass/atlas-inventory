import { expect, test, type APIRequestContext, type Page } from "@playwright/test";

import { cookieValues, fillLogin, newUser, register, type TestUser } from "./helpers";

const API = "http://localhost:8080";
const DESKTOP = { width: 1280, height: 800 };
const MOBILE = { width: 375, height: 800 };

// A distinct, valid CNPJ per call: 12 random digits plus the two check digits.
function validCnpj(): string {
  const digits = Array.from({ length: 12 }, () => Math.floor(Math.random() * 10));
  for (const weights of [
    [5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2],
    [6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2],
  ]) {
    const sum = weights.reduce((acc, w, i) => acc + w * digits[i], 0);
    const rest = sum % 11;
    digits.push(rest < 2 ? 0 : 11 - rest);
  }
  return digits.join("");
}

const uniqueName = (prefix: string) => `${prefix} ${Math.random().toString(36).slice(2, 10)}`;

const hasHorizontalScroll = (page: Page) =>
  page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth);

const auth = (token: string) => ({ Authorization: `Bearer ${token}` });

async function apiCompany(request: APIRequestContext, token: string, name = uniqueName("Empresa")) {
  const res = await request.post(`${API}/api/companies`, {
    headers: auth(token),
    data: { name, cnpj: validCnpj() },
  });
  expect(res.status()).toBe(201);
  return { id: (await res.json()).id as string, name };
}

async function apiWarehouse(
  request: APIRequestContext,
  token: string,
  companyId: string,
  code = `D${Math.random().toString(36).slice(2, 8).toUpperCase()}`,
) {
  const res = await request.post(`${API}/api/companies/${companyId}/warehouses`, {
    headers: auth(token),
    data: { code, name: uniqueName("Depósito") },
  });
  expect(res.status()).toBe(201);
  return { code };
}

// Registers an owner in the browser and returns the access token for API setup.
async function ownerWithToken(page: Page) {
  const user = await register(page);
  const { access } = await cookieValues(page);
  return { user, token: access! };
}

const confirmArchive = async (page: Page) => {
  const dialog = page.getByRole("alertdialog");
  await dialog.getByRole("button", { name: "Arquivar" }).click();
  await expect(dialog).toBeHidden();
};

test.describe("companies", () => {
  test("creates a company and opens it", async ({ page }) => {
    await register(page);
    await page.goto("/empresas");
    await expect(page.getByText("Nenhuma empresa cadastrada.")).toBeVisible();

    const name = uniqueName("Acme");
    await page.getByRole("button", { name: "Nova empresa" }).click();
    await page.getByLabel("Nome", { exact: true }).fill(name);
    await page.getByLabel("CNPJ (opcional)").fill(validCnpj());
    await page.getByRole("button", { name: "Criar empresa" }).click();

    const link = page.getByRole("link", { name });
    await expect(link).toBeVisible();
    await expect(page.getByText("Ativa")).toBeVisible();
    await link.click();
    await expect(page).toHaveURL(/\/empresas\/[0-9a-f-]{36}$/);
    await expect(page.getByRole("heading", { name })).toBeVisible();
    await expect(page.getByText("Nenhum depósito cadastrado.")).toBeVisible();
  });

  test("shows the duplicate company name message", async ({ page, request }) => {
    const { token } = await ownerWithToken(page);
    const { name } = await apiCompany(request, token);
    await page.goto("/empresas");
    await page.getByRole("button", { name: "Nova empresa" }).click();
    await page.getByLabel("Nome", { exact: true }).fill(name);
    await page.getByRole("button", { name: "Criar empresa" }).click();
    await expect(page.getByText("Já existe uma empresa com este nome.")).toBeVisible();
  });

  test("creates a warehouse in the company", async ({ page, request }) => {
    const { token } = await ownerWithToken(page);
    const { id } = await apiCompany(request, token);
    await page.goto(`/empresas/${id}`);

    await page.getByRole("button", { name: "Novo depósito" }).click();
    await page.getByLabel("Código").fill("CD-01");
    await page.getByLabel("Nome", { exact: true }).fill("Central");
    await page.getByRole("button", { name: "Criar depósito" }).click();

    const row = page.getByRole("row", { name: /CD-01/ });
    await expect(row).toBeVisible();
    await expect(row.getByText("Central")).toBeVisible();
    await expect(row.getByText("Ativo")).toBeVisible();
  });

  test("archives and unarchives a warehouse", async ({ page, request }) => {
    const { token } = await ownerWithToken(page);
    const { id } = await apiCompany(request, token);
    const { code } = await apiWarehouse(request, token, id);
    await page.goto(`/empresas/${id}`);

    const row = page.getByRole("row", { name: new RegExp(code) });
    await row.getByRole("button", { name: "Arquivar" }).click();
    await expect(page.getByRole("alertdialog")).toContainText("Arquivar depósito?");
    await confirmArchive(page);
    await expect(page.getByText("Nenhum depósito cadastrado.")).toBeVisible();

    await page.getByRole("link", { name: "Mostrar arquivados" }).click();
    await expect(page).toHaveURL(/arquivados=1/);
    await expect(row.getByText("Arquivado")).toBeVisible();

    await row.getByRole("button", { name: "Desarquivar" }).click();
    await expect(row.getByText("Ativo")).toBeVisible();
    await expect(row.getByRole("button", { name: "Arquivar" })).toBeVisible();
  });

  test("archives and unarchives a company from its page", async ({ page, request }) => {
    const { token } = await ownerWithToken(page);
    const { id, name } = await apiCompany(request, token);
    await page.goto(`/empresas/${id}`);

    await page.getByRole("button", { name: "Arquivar" }).click();
    await expect(page.getByRole("alertdialog")).toContainText("Arquivar empresa?");
    await confirmArchive(page);
    await expect(page).toHaveURL(`/empresas/${id}`);
    await expect(page.getByText("Arquivada")).toBeVisible();
    await expect(page.getByRole("button", { name: "Novo depósito" })).toBeHidden();

    await page.getByRole("link", { name: "Voltar para empresas" }).click();
    await expect(page.getByText("Nenhuma empresa cadastrada.")).toBeVisible();
    await page.getByRole("link", { name: "Mostrar arquivadas" }).click();
    await expect(page).toHaveURL(/arquivadas=1/);
    await expect(page.getByRole("row", { name: new RegExp(name) }).getByText("Arquivada")).toBeVisible();

    await page.getByRole("link", { name }).click();
    await page.getByRole("button", { name: "Desarquivar" }).click();
    await expect(page.getByText("Ativa", { exact: true })).toBeVisible();
    await expect(page.getByRole("button", { name: "Novo depósito" })).toBeVisible();
  });

  test("shows not found for an unknown company", async ({ page }) => {
    await register(page);
    await page.goto("/empresas/00000000-0000-0000-0000-000000000000");
    await expect(page.getByText("This page could not be found.")).toBeVisible();
  });

  test("shows a member only the granted company without write controls", async ({ page, request }) => {
    const owner = newUser();
    const registered = await request.post(`${API}/api/auth/register`, {
      data: {
        name: owner.name,
        email: owner.email,
        password: owner.password,
        organizationName: owner.organizationName,
        organizationSlug: owner.slug,
      },
    });
    expect(registered.status()).toBe(201);
    const ownerToken = (await registered.json()).accessToken as string;
    const granted = await apiCompany(request, ownerToken);
    const hidden = await apiCompany(request, ownerToken);
    await apiWarehouse(request, ownerToken, granted.id);

    const member: TestUser = newUser();
    const memberRegistered = await request.post(`${API}/api/auth/register`, {
      data: {
        name: member.name,
        email: member.email,
        password: member.password,
        organizationName: member.organizationName,
        organizationSlug: member.slug,
      },
    });
    expect(memberRegistered.status()).toBe(201);
    const added = await request.post(`${API}/api/members`, {
      headers: auth(ownerToken),
      data: { email: member.email, role: "MEMBER" },
    });
    expect(added.ok()).toBe(true);
    const members = await (await request.get(`${API}/api/members`, { headers: auth(ownerToken) })).json();
    const userId = members.find((m: { email: string }) => m.email === member.email).userId;
    const access = await request.post(`${API}/api/members/${userId}/access`, {
      headers: auth(ownerToken),
      data: { companyId: granted.id },
    });
    expect(access.ok()).toBe(true);

    await page.goto("/entrar");
    await fillLogin(page, member.email, member.password);
    await expect(page).toHaveURL("/");
    await page.getByRole("button", { name: /^Org / }).click();
    await page.getByRole("menuitem", { name: owner.organizationName }).click();
    await expect(page.getByRole("button", { name: owner.organizationName })).toBeVisible();

    await page.goto("/empresas");
    await expect(page.getByRole("link", { name: granted.name })).toBeVisible();
    await expect(page.getByRole("link", { name: hidden.name })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Nova empresa" })).toHaveCount(0);

    await page.getByRole("link", { name: granted.name }).click();
    await expect(page.getByRole("heading", { name: granted.name })).toBeVisible();
    await expect(page.getByRole("row")).toHaveCount(2);
    for (const label of ["Novo depósito", "Arquivar", "Desarquivar"]) {
      await expect(page.getByRole("button", { name: label })).toHaveCount(0);
    }
  });

  test("never exposes tokens to the page", async ({ page, request }) => {
    const { token } = await ownerWithToken(page);
    const { id } = await apiCompany(request, token);
    const before = await cookieValues(page);

    const bodies: Promise<string>[] = [];
    page.on("response", (response) => {
      if (response.status() >= 300 && response.status() < 400) return;
      bodies.push(
        (async () => {
          const headers = Object.entries(await response.allHeaders())
            .filter(([name]) => name !== "set-cookie")
            .map(([name, value]) => `${name}: ${value}`);
          // A body is gone once the test navigates away (documents, prefetches); documents are checked from the DOM below.
          const body = await response.text().catch((error: Error) => {
            if (/navigated away|No data found/.test(error.message)) return "";
            throw error;
          });
          return [...headers, body].join("\n");
        })(),
      );
    });

    const documents: string[] = [];
    await page.goto("/empresas");
    documents.push(await page.content());
    await page.goto(`/empresas/${id}`);
    await page.getByRole("button", { name: "Novo depósito" }).click();
    await page.getByLabel("Código").fill("TK-01");
    await page.getByLabel("Nome", { exact: true }).fill("Token check");
    await page.getByRole("button", { name: "Criar depósito" }).click();
    const row = page.getByRole("row", { name: /TK-01/ });
    await row.getByRole("button", { name: "Arquivar" }).click();
    await confirmArchive(page);
    await page.getByRole("link", { name: "Mostrar arquivados" }).click();
    await expect(row.getByText("Arquivado")).toBeVisible();

    documents.push(await page.content());
    const after = await cookieValues(page);
    const texts = [...(await Promise.all(bodies)), ...documents];
    expect(texts.length).toBeGreaterThan(0);
    for (const text of texts) {
      for (const secret of [before.access, before.refresh, after.access, after.refresh]) {
        expect(text).not.toContain(secret!);
      }
    }
    expect(await page.evaluate(() => document.cookie)).not.toContain("atlas_");
  });

  test("has no horizontal page scroll at 375 px", async ({ page, request }) => {
    await page.setViewportSize(MOBILE);
    const { token } = await ownerWithToken(page);
    const { id } = await apiCompany(request, token, `Empresa com um nome extremamente longo ${"x".repeat(60)}`);
    await apiWarehouse(request, token, id, "CD-LONGO-0123456789");

    await page.goto("/empresas");
    await expect(page.getByRole("link", { name: /Empresa com um nome/ })).toBeVisible();
    expect(await hasHorizontalScroll(page)).toBe(false);

    await page.goto(`/empresas/${id}`);
    await expect(page.getByRole("row", { name: /CD-LONGO/ })).toBeVisible();
    expect(await hasHorizontalScroll(page)).toBe(false);
  });

  test("shows every column at 1280 px", async ({ page, request }) => {
    await page.setViewportSize(DESKTOP);
    const { token } = await ownerWithToken(page);
    const { id } = await apiCompany(request, token);
    await apiWarehouse(request, token, id);

    const noClipping = () =>
      page.evaluate(() =>
        [...document.querySelectorAll(".overflow-x-auto")].every((el) => el.scrollWidth <= el.clientWidth),
      );

    await page.goto("/empresas");
    for (const column of ["Nome", "CNPJ", "Status"]) {
      await expect(page.getByRole("columnheader", { name: column })).toBeVisible();
    }
    expect(await noClipping()).toBe(true);
    expect(await hasHorizontalScroll(page)).toBe(false);

    await page.goto(`/empresas/${id}`);
    for (const column of ["Código", "Nome", "Status", "Ações"]) {
      await expect(page.getByRole("columnheader", { name: column })).toBeVisible();
    }
    expect(await noClipping()).toBe(true);
    expect(await hasHorizontalScroll(page)).toBe(false);
  });
});
