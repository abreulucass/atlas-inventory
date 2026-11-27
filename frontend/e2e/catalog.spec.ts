import { expect, test, type APIRequestContext, type Page } from "@playwright/test";

import { cookieValues, fillLogin, newUser, register, type TestUser } from "./helpers";

const API = "http://localhost:8080";
const DESKTOP = { width: 1280, height: 800 };
const MOBILE = { width: 375, height: 800 };

const uniqueId = () => Math.random().toString(36).slice(2, 10);
const uniqueName = (prefix: string) => `${prefix} ${uniqueId()}`;
const uniqueSku = () => `SKU-${uniqueId().toUpperCase()}`;

const auth = (token: string) => ({ Authorization: `Bearer ${token}` });

const hasHorizontalScroll = (page: Page) =>
  page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth);

const tableContainers = (page: Page) =>
  page.evaluate(() =>
    [...document.querySelectorAll('[data-slot="table-container"]')].map((el) => ({
      overflowX: getComputedStyle(el).overflowX,
      scrollWidth: el.scrollWidth,
      clientWidth: el.clientWidth,
    })),
  );

async function apiCategory(request: APIRequestContext, token: string, name = uniqueName("Categoria")) {
  const res = await request.post(`${API}/api/categories`, { headers: auth(token), data: { name } });
  expect(res.status()).toBe(201);
  return { id: (await res.json()).id as string, name };
}

async function apiProduct(
  request: APIRequestContext,
  token: string,
  { sku = uniqueSku(), name = uniqueName("Produto"), categoryId = null as string | null } = {},
) {
  const res = await request.post(`${API}/api/products`, {
    headers: auth(token),
    data: { sku, name, unit: "UN", categoryId },
  });
  expect(res.status()).toBe(201);
  return { id: (await res.json()).id as string, sku, name };
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

const filter = async (page: Page, { q, status }: { q?: string; status?: string }) => {
  if (q !== undefined) await page.getByLabel("Buscar por nome ou SKU").fill(q);
  if (status) await page.getByLabel("Status", { exact: true }).selectOption({ label: status });
  await page.getByRole("button", { name: "Filtrar" }).click();
};

test.describe("catalog", () => {
  test("owner creates, archives and unarchives a category", async ({ page }) => {
    await register(page);
    await page.goto("/categorias");
    await expect(page.getByText("Nenhuma categoria cadastrada.")).toBeVisible();

    const name = uniqueName("Bebidas");
    await page.getByRole("button", { name: "Nova categoria" }).click();
    await page.getByLabel("Nome", { exact: true }).fill(name);
    await page.getByRole("button", { name: "Criar categoria" }).click();
    const row = page.getByRole("row", { name: new RegExp(name) });
    await expect(row.getByText("Ativa")).toBeVisible();

    await row.getByRole("button", { name: "Arquivar" }).click();
    await expect(page.getByRole("alertdialog")).toContainText("Arquivar categoria?");
    await confirmArchive(page);
    await expect(page.getByText("Nenhuma categoria cadastrada.")).toBeVisible();

    await page.getByRole("link", { name: "Mostrar arquivadas" }).click();
    await expect(page).toHaveURL(/arquivadas=1/);
    await expect(row.getByText("Arquivada")).toBeVisible();

    await row.getByRole("button", { name: "Desarquivar" }).click();
    await expect(row.getByText("Ativa")).toBeVisible();
    await expect(row.getByRole("button", { name: "Arquivar" })).toBeVisible();
  });

  test("owner creates, finds, edits, archives and unarchives a product", async ({ page, request }) => {
    const { token } = await ownerWithToken(page);
    const category = await apiCategory(request, token);
    const other = await apiProduct(request, token);
    await page.goto("/produtos");
    await expect(page.getByRole("row", { name: new RegExp(other.sku) })).toBeVisible();

    const sku = uniqueSku();
    const name = uniqueName("Arroz");
    await page.getByRole("button", { name: "Novo produto" }).click();
    await page.getByLabel("SKU", { exact: true }).fill(sku);
    await page.getByLabel("Nome", { exact: true }).fill(name);
    await page.getByLabel("Unidade").selectOption({ label: "Quilograma (KG)" });
    await page.getByLabel("Categoria (opcional)").selectOption({ label: category.name });
    await page.getByRole("button", { name: "Criar produto" }).click();
    const row = page.getByRole("row", { name: new RegExp(sku) });
    await expect(row).toBeVisible();
    await expect(row).toContainText(category.name);
    await expect(row).toContainText("Quilograma (KG)");

    // Search by SKU prefix: only the new product stays.
    await filter(page, { q: sku.slice(0, 8) });
    await expect(page).toHaveURL(/q=SKU-/);
    await expect(row).toBeVisible();
    await expect(page.getByRole("row", { name: new RegExp(other.sku) })).toHaveCount(0);

    // Edit the name.
    const renamed = uniqueName("Arroz integral");
    await row.getByRole("button", { name: "Editar" }).click();
    const dialog = page.getByRole("dialog");
    await expect(dialog).toContainText("Editar produto");
    await dialog.getByLabel("Nome", { exact: true }).fill(renamed);
    await dialog.getByRole("button", { name: "Salvar" }).click();
    await expect(dialog).toBeHidden();
    await expect(row).toContainText(renamed);

    // Archive with confirmation.
    await row.getByRole("button", { name: "Arquivar" }).click();
    await expect(page.getByRole("alertdialog")).toContainText("Arquivar produto?");
    await confirmArchive(page);
    await expect(page.getByText("Nenhum produto encontrado.")).toBeVisible();

    await filter(page, { status: "Arquivados" });
    await expect(page).toHaveURL(/status=arquivados/);
    await expect(row.getByText("Arquivado")).toBeVisible();
    await expect(row.getByRole("button", { name: "Editar" })).toHaveCount(0);

    await row.getByRole("button", { name: "Desarquivar" }).click();
    await expect(page.getByText("Nenhum produto encontrado.")).toBeVisible();
    await filter(page, { status: "Ativos" });
    await expect(row.getByText("Ativo", { exact: true })).toBeVisible();
  });

  test("shows the duplicate SKU message", async ({ page, request }) => {
    const { token } = await ownerWithToken(page);
    const { sku } = await apiProduct(request, token);
    await page.goto("/produtos");
    await page.getByRole("button", { name: "Novo produto" }).click();
    await page.getByLabel("SKU", { exact: true }).fill(sku);
    await page.getByLabel("Nome", { exact: true }).fill(uniqueName("Repetido"));
    await page.getByLabel("Unidade").selectOption({ label: "Unidade (UN)" });
    await page.getByRole("button", { name: "Criar produto" }).click();
    await expect(page.getByText("Já existe um produto com este SKU.")).toBeVisible();
  });

  test("paginates keeping the search", async ({ page, request }) => {
    const { token } = await ownerWithToken(page);
    const tag = uniqueId().toUpperCase();
    for (let i = 1; i <= 21; i++) {
      const n = String(i).padStart(2, "0");
      await apiProduct(request, token, { sku: `PG${tag}-${n}`, name: `Item ${tag} ${n}` });
    }
    await apiProduct(request, token); // outside the search

    await page.goto("/produtos");
    await filter(page, { q: `PG${tag}` });
    await expect(page.getByText("Página 1 de 2")).toBeVisible();
    await expect(page.getByRole("row")).toHaveCount(21); // header + 20
    await page.getByRole("link", { name: "Próxima" }).click();
    await expect(page).toHaveURL(new RegExp(`q=PG${tag}.*pagina=2`));
    await expect(page.getByText("Página 2 de 2")).toBeVisible();
    await expect(page.getByRole("row")).toHaveCount(2);
    await expect(page.getByRole("row", { name: new RegExp(`PG${tag}-21`) })).toBeVisible();
    await expect(page.getByLabel("Buscar por nome ou SKU")).toHaveValue(`PG${tag}`);
  });

  test("shows a member the catalog without write controls", async ({ page, request }) => {
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
    const category = await apiCategory(request, ownerToken);
    const product = await apiProduct(request, ownerToken, { categoryId: category.id });

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

    await page.goto("/entrar");
    await fillLogin(page, member.email, member.password);
    await expect(page).toHaveURL("/");
    await page.getByRole("button", { name: /^Org / }).click();
    await page.getByRole("menuitem", { name: owner.organizationName }).click();
    await expect(page.getByRole("button", { name: owner.organizationName })).toBeVisible();

    await page.goto("/produtos");
    const row = page.getByRole("row", { name: new RegExp(product.sku) });
    await expect(row).toContainText(category.name);
    for (const label of ["Novo produto", "Editar", "Arquivar", "Desarquivar"]) {
      await expect(page.getByRole("button", { name: label })).toHaveCount(0);
    }

    await page.goto("/categorias");
    await expect(page.getByRole("row", { name: new RegExp(category.name) })).toBeVisible();
    for (const label of ["Nova categoria", "Arquivar", "Desarquivar"]) {
      await expect(page.getByRole("button", { name: label })).toHaveCount(0);
    }
  });

  test("never sends a token to the browser", async ({ page, request }) => {
    const { token } = await ownerWithToken(page);
    const product = await apiProduct(request, token);
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

    await page.goto("/categorias");
    await settle();
    const categoryName = uniqueName("Token");
    await page.getByRole("button", { name: "Nova categoria" }).click();
    await page.getByLabel("Nome", { exact: true }).fill(categoryName);
    await page.getByRole("button", { name: "Criar categoria" }).click();
    const categoryRow = page.getByRole("row", { name: new RegExp(categoryName) });
    await categoryRow.getByRole("button", { name: "Arquivar" }).click();
    await confirmArchive(page);
    await page.getByRole("link", { name: "Mostrar arquivadas" }).click();
    await categoryRow.getByRole("button", { name: "Desarquivar" }).click();
    await expect(categoryRow.getByText("Ativa")).toBeVisible();
    await settle();
    documents.push(await page.content());

    await page.goto("/produtos");
    const row = page.getByRole("row", { name: new RegExp(product.sku) });
    await row.getByRole("button", { name: "Editar" }).click();
    await page.getByRole("dialog").getByLabel("Nome", { exact: true }).fill(uniqueName("Token edit"));
    await page.getByRole("dialog").getByRole("button", { name: "Salvar" }).click();
    await expect(page.getByRole("dialog")).toBeHidden();
    await row.getByRole("button", { name: "Arquivar" }).click();
    await confirmArchive(page);
    await settle();
    documents.push(await page.content());

    expect(await cookieValues(page)).toEqual(before);

    const texts = [...captured, ...documents];
    expect(texts.length).toBeGreaterThan(5);
    for (const text of texts) {
      for (const secret of secrets) expect(text).not.toContain(secret);
    }
    expect(await page.evaluate(() => document.cookie)).not.toContain("atlas_");
    expect(apiRequests).toEqual([]);
  });

  test("has no page-level horizontal scroll at 375 px", async ({ page, request }) => {
    await page.setViewportSize(MOBILE);
    const { token } = await ownerWithToken(page);
    const longName = "W".repeat(200);
    const category = await apiCategory(request, token, "C".repeat(80));
    const product = await apiProduct(request, token, { name: longName, categoryId: category.id });

    const check = async () => {
      expect(await hasHorizontalScroll(page)).toBe(false);
      const [container] = await tableContainers(page);
      expect(container.overflowX).toBe("auto");
    };

    await page.goto("/categorias");
    await expect(page.getByRole("row", { name: new RegExp(category.name) })).toBeVisible();
    await check();

    await page.goto("/produtos");
    await expect(page.getByRole("row", { name: new RegExp(product.sku) })).toBeVisible();
    await check();
  });

  test("shows every products column at 1280 px", async ({ page, request }) => {
    await page.setViewportSize(DESKTOP);
    const { token } = await ownerWithToken(page);
    const category = await apiCategory(request, token);
    const product = await apiProduct(request, token, { name: "W".repeat(200), categoryId: category.id });

    await page.goto("/produtos");
    await expect(page.getByRole("row", { name: new RegExp(product.sku) })).toBeVisible();
    for (const column of ["SKU", "Nome", "Categoria", "Unidade", "Status", "Ações"]) {
      await expect(page.getByRole("columnheader", { name: column })).toBeVisible();
    }
    const containers = await tableContainers(page);
    expect(containers).toHaveLength(1);
    for (const c of containers) expect(c.scrollWidth).toBeLessThanOrEqual(c.clientWidth);
    expect(await hasHorizontalScroll(page)).toBe(false);
  });
});
