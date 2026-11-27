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

// Exact-limit values: a 120-character name without spaces and a 20-character warehouse code.
const LONG_NAME = "W".repeat(120);
const LONG_CODE = "C".repeat(20);

const tableContainers = (page: Page) =>
  page.evaluate(() =>
    [...document.querySelectorAll('[data-slot="table-container"]')].map((el) => ({
      overflowX: getComputedStyle(el).overflowX,
      scrollWidth: el.scrollWidth,
      clientWidth: el.clientWidth,
    })),
  );

// Next keeps the previous route mounted but hidden after a client navigation: match what the user sees.
const visibleText = (page: Page, text: string) =>
  page.getByText(text, { exact: true }).locator("visible=true");

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
  name = uniqueName("Depósito"),
) {
  const res = await request.post(`${API}/api/companies/${companyId}/warehouses`, {
    headers: auth(token),
    data: { code, name },
  });
  expect(res.status()).toBe(201);
  return { code, id: (await res.json()).id as string };
}

// Registers an owner in the browser and returns the access token for API setup.
async function ownerWithToken(page: Page) {
  const user = await register(page);
  const { access } = await cookieValues(page);
  return { user, token: access! };
}

const apiArchive = async (request: APIRequestContext, token: string, path: string) =>
  expect((await request.post(`${API}${path}/archive`, { headers: auth(token) })).status()).toBe(204);

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
    const grantedArchived = await apiCompany(request, ownerToken);
    const hidden = await apiCompany(request, ownerToken);
    await apiWarehouse(request, ownerToken, granted.id);
    const archivedWarehouse = await apiWarehouse(request, ownerToken, granted.id);
    await apiArchive(request, ownerToken, `/api/companies/${granted.id}/warehouses/${archivedWarehouse.id}`);
    await apiArchive(request, ownerToken, `/api/companies/${grantedArchived.id}`);

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
    for (const companyId of [granted.id, grantedArchived.id]) {
      const access = await request.post(`${API}/api/members/${userId}/access`, {
        headers: auth(ownerToken),
        data: { companyId },
      });
      expect(access.ok()).toBe(true);
    }

    await page.goto("/entrar");
    await fillLogin(page, member.email, member.password);
    await expect(page).toHaveURL("/");
    await page.getByRole("button", { name: /^Org / }).click();
    await page.getByRole("menuitem", { name: owner.organizationName }).click();
    await expect(page.getByRole("button", { name: owner.organizationName })).toBeVisible();

    const noWriteControls = async () => {
      for (const label of ["Novo depósito", "Arquivar", "Desarquivar"]) {
        await expect(page.getByRole("button", { name: label })).toHaveCount(0);
      }
    };

    await page.goto("/empresas");
    await expect(page.getByRole("link", { name: granted.name })).toBeVisible();
    await expect(page.getByRole("link", { name: grantedArchived.name })).toHaveCount(0);
    await expect(page.getByRole("link", { name: hidden.name })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Nova empresa" })).toHaveCount(0);

    // Archived items, filter on: still no write control.
    await page.getByRole("link", { name: "Mostrar arquivadas" }).click();
    await expect(page).toHaveURL(/arquivadas=1/);
    const archivedRow = page.getByRole("row", { name: new RegExp(grantedArchived.name) });
    await expect(archivedRow.getByText("Arquivada")).toBeVisible();
    await expect(page.getByRole("link", { name: hidden.name })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Nova empresa" })).toHaveCount(0);

    await archivedRow.getByRole("link", { name: grantedArchived.name }).click();
    await expect(page.getByRole("heading", { name: grantedArchived.name })).toBeVisible();
    await expect(visibleText(page, "Arquivada")).toBeVisible();
    await noWriteControls();

    await page.goto(`/empresas/${granted.id}?arquivados=1`);
    await expect(page.getByRole("heading", { name: granted.name })).toBeVisible();
    await expect(page.getByRole("row")).toHaveCount(3);
    await expect(visibleText(page, "Arquivado")).toBeVisible();
    await expect(visibleText(page, "Ativo")).toBeVisible();
    await noWriteControls();
  });

  test("never exposes tokens to the page", async ({ page, request }) => {
    const { token } = await ownerWithToken(page);
    const { id } = await apiCompany(request, token);
    const before = await cookieValues(page);
    const secrets = [before.access!, before.refresh!];

    // Every response, redirects and errors included, read in the route so no body is lost to a navigation.
    const captured: string[] = [];
    await page.route("**/*", async (route) => {
      const response = await route.fetch({ maxRedirects: 0 });
      const body = await response.text();
      const headers = response
        .headersArray()
        .filter(({ name }) => name.toLowerCase() !== "set-cookie")
        .map(({ name, value }) => `${name}: ${value}`);
      captured.push([`${response.status()} ${response.url()}`, ...headers, body].join("\n"));
      await route.fulfill({ response, body });
    });
    const apiRequests: string[] = [];
    page.on("request", (req) => {
      if (req.url().startsWith(API)) apiRequests.push(req.url());
    });
    const settle = () => page.waitForLoadState("networkidle");

    const documents: string[] = [];
    const snapshot = async () => documents.push(await page.content());

    await page.goto("/empresas");
    await settle();
    // Company: create, archive, unarchive.
    const name = uniqueName("Token");
    await page.getByRole("button", { name: "Nova empresa" }).click();
    await page.getByLabel("Nome", { exact: true }).fill(name);
    await page.getByRole("button", { name: "Criar empresa" }).click();
    await page.getByRole("link", { name }).click();
    await expect(page.getByRole("heading", { name })).toBeVisible();
    await page.getByRole("button", { name: "Arquivar" }).click();
    await confirmArchive(page);
    await expect(visibleText(page, "Arquivada")).toBeVisible();
    await page.getByRole("button", { name: "Desarquivar" }).click();
    await expect(visibleText(page, "Ativa")).toBeVisible();
    await settle();
    await snapshot();

    // Warehouse: create, archive, unarchive.
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
    await row.getByRole("button", { name: "Desarquivar" }).click();
    await expect(row.getByText("Ativo")).toBeVisible();
    await settle();
    await snapshot();

    // The sessions cookies did not change while writing.
    const after = await cookieValues(page);
    expect(after).toEqual(before);

    // A redirect (a signed-in user leaving the login page) and an error response (404 document).
    await page.goto("/entrar");
    await expect(page).toHaveURL("/");
    await settle();
    await page.goto("/empresas/00000000-0000-0000-0000-000000000000");
    await settle();
    await snapshot();
    expect(await page.evaluate(async () => (await fetch("/does-not-exist")).status)).toBe(404);

    const texts = [...captured, ...documents];
    expect(texts.length).toBeGreaterThan(10);
    expect(texts.some((text) => text.startsWith("303 ") || text.startsWith("307 "))).toBe(true);
    expect(texts.some((text) => /^(4|5)\d\d /.test(text))).toBe(true);
    for (const text of texts) {
      for (const secret of secrets) expect(text).not.toContain(secret);
    }
    expect(await page.evaluate(() => document.cookie)).not.toContain("atlas_");
    expect(apiRequests).toEqual([]);
  });

  test("has no horizontal page scroll at 375 px", async ({ page, request }) => {
    await page.setViewportSize(MOBILE);
    const { token } = await ownerWithToken(page);
    const { id, name } = await apiCompany(request, token, LONG_NAME);
    const { code } = await apiWarehouse(request, token, id, LONG_CODE, LONG_NAME);

    // The document never scrolls sideways; the table container scrolls itself if the table is wider.
    const check = async () => {
      expect(await hasHorizontalScroll(page)).toBe(false);
      const [container] = await tableContainers(page);
      expect(container.overflowX).toBe("auto");
      return container;
    };

    await page.goto("/empresas");
    await expect(page.getByRole("link", { name })).toBeVisible();
    await check();

    await page.goto(`/empresas/${id}`);
    await expect(page.getByRole("row", { name: new RegExp(code) })).toBeVisible();
    const warehouses = await check();
    // Code, name, status and actions cannot fit in 375 px: the container scrolls, not the page.
    expect(warehouses.scrollWidth).toBeGreaterThan(warehouses.clientWidth);
  });

  test("shows every column at 1280 px", async ({ page, request }) => {
    await page.setViewportSize(DESKTOP);
    const { token } = await ownerWithToken(page);
    const { id, name } = await apiCompany(request, token, LONG_NAME);
    const { code } = await apiWarehouse(request, token, id, LONG_CODE, LONG_NAME);

    const fits = async () => {
      const containers = await tableContainers(page);
      expect(containers).toHaveLength(1);
      for (const c of containers) expect(c.scrollWidth).toBeLessThanOrEqual(c.clientWidth);
      expect(await hasHorizontalScroll(page)).toBe(false);
    };

    await page.goto("/empresas");
    await expect(page.getByRole("link", { name })).toBeVisible();
    for (const column of ["Nome", "CNPJ", "Status"]) {
      await expect(page.getByRole("columnheader", { name: column })).toBeVisible();
    }
    await fits();

    await page.goto(`/empresas/${id}`);
    await expect(page.getByRole("row", { name: new RegExp(code) })).toBeVisible();
    for (const column of ["Código", "Nome", "Status", "Ações"]) {
      await expect(page.getByRole("columnheader", { name: column })).toBeVisible();
    }
    await fits();
  });
});
