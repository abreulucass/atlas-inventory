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

// A select applies at once (with whatever is typed in the search); the search text alone needs Filtrar.
const filter = async (
  page: Page,
  { q, status, category }: { q?: string; status?: string; category?: string },
) => {
  if (q !== undefined) await page.getByLabel("Buscar por nome ou SKU").fill(q);
  if (category !== undefined) {
    await page.getByLabel("Categoria", { exact: true }).selectOption({ label: category });
    await page.waitForURL((url) => Boolean(url.searchParams.get("categoria")));
  }
  if (status) {
    await page.getByLabel("Status", { exact: true }).selectOption({ label: status });
    await page.waitForURL((url) => url.searchParams.get("status") === status.toLowerCase());
  }
  if (category === undefined && !status) await page.getByRole("button", { name: "Filtrar" }).click();
};

async function apiArchive(request: APIRequestContext, token: string, kind: "categories" | "products", id: string) {
  const res = await request.post(`${API}/api/${kind}/${id}/archive`, { headers: auth(token) });
  expect(res.status()).toBe(204);
}

const queryOf = (page: Page) => Object.fromEntries(new URL(page.url()).searchParams);

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
    const otherCategory = await apiCategory(request, token);
    const tag = uniqueId().toUpperCase();
    // Decoys: each one shows up in some view only if the search or the category filter is lost.
    const wrongCategory = await apiProduct(request, token, { name: `Arroz ${tag} outra`, categoryId: otherCategory.id });
    const noMatch = await apiProduct(request, token, { name: uniqueName("Feijão"), categoryId: category.id });
    const archivedWrongCategory = await apiProduct(request, token, {
      name: `Arroz ${tag} arquivado outra`,
      categoryId: otherCategory.id,
    });
    const archivedNoMatch = await apiProduct(request, token, { name: uniqueName("Milho"), categoryId: category.id });
    await apiArchive(request, token, "products", archivedWrongCategory.id);
    await apiArchive(request, token, "products", archivedNoMatch.id);
    const onlyDecoysAbsent = async () => {
      for (const decoy of [wrongCategory, noMatch, archivedWrongCategory, archivedNoMatch]) {
        await expect(page.getByRole("row", { name: new RegExp(decoy.sku) })).toHaveCount(0);
      }
    };
    await page.goto("/produtos");
    await expect(page.getByRole("row", { name: new RegExp(noMatch.sku) })).toBeVisible();

    const sku = uniqueSku();
    const name = `Arroz ${tag} ${uniqueId()}`;
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

    // Search and category together: only the new product stays.
    await filter(page, { q: tag, category: category.name });
    const filters = { q: tag, categoria: category.id, status: "ativos" };
    expect(queryOf(page)).toEqual(filters);
    await expect(row).toBeVisible();
    await onlyDecoysAbsent();

    // Edit the name: the filters stay in the URL.
    const renamed = `Arroz ${tag} integral ${uniqueId()}`;
    await row.getByRole("button", { name: "Editar" }).click();
    const dialog = page.getByRole("dialog");
    await expect(dialog).toContainText("Editar produto");
    await dialog.getByLabel("Nome", { exact: true }).fill(renamed);
    await dialog.getByRole("button", { name: "Salvar" }).click();
    await expect(dialog).toBeHidden();
    await expect(row).toContainText(renamed);
    expect(queryOf(page)).toEqual(filters);
    await onlyDecoysAbsent();

    // Archive with confirmation: same URL, the row leaves, no decoy appears.
    await row.getByRole("button", { name: "Arquivar" }).click();
    await expect(page.getByRole("alertdialog")).toContainText("Arquivar produto?");
    await confirmArchive(page);
    await expect(page.getByText("Nenhum produto encontrado.")).toBeVisible();
    expect(queryOf(page)).toEqual(filters);
    await onlyDecoysAbsent();

    // Arquivados: the same q and categoria with the new status; only the archived row of this search.
    await filter(page, { status: "Arquivados" });
    const archivedFilters = { ...filters, status: "arquivados" };
    expect(queryOf(page)).toEqual(archivedFilters);
    await expect(row.getByText("Arquivado")).toBeVisible();
    await expect(row.getByRole("button", { name: "Editar" })).toHaveCount(0);
    await onlyDecoysAbsent();

    // Unarchive: the same URL, the row leaves this view, no decoy appears.
    await row.getByRole("button", { name: "Desarquivar" }).click();
    await expect(page.getByText("Nenhum produto encontrado.")).toBeVisible();
    expect(queryOf(page)).toEqual(archivedFilters);
    await onlyDecoysAbsent();

    await filter(page, { status: "Ativos" });
    expect(queryOf(page)).toEqual(filters);
    await expect(row.getByText("Ativo", { exact: true })).toBeVisible();
    await onlyDecoysAbsent();
  });

  test("applies the products filters as soon as a select changes, from a paginated url", async ({ page, request }) => {
    const { token } = await ownerWithToken(page);
    const category = await apiCategory(request, token);
    const tag = uniqueId().toUpperCase();
    for (let i = 1; i <= 21; i++) {
      const n = String(i).padStart(2, "0");
      await apiProduct(request, token, { sku: `SEL${tag}-${n}`, name: `Item ${tag} ${n}`, categoryId: category.id });
    }
    await page.goto(`/produtos?q=${tag}&pagina=2`);
    await expect(page.getByText("Página 2 de 2")).toBeVisible();

    await page.getByLabel("Categoria", { exact: true }).selectOption({ label: category.name });
    await page.waitForURL((url) => url.searchParams.get("categoria") === category.id);
    expect(queryOf(page)).toEqual({ q: tag, categoria: category.id, status: "ativos" });
    await expect(page.getByText("Página 1 de 2")).toBeVisible();

    await page.goto(`/produtos?q=${tag}&pagina=2`);
    await page.getByLabel("Status", { exact: true }).selectOption({ label: "Todos" });
    await page.waitForURL(/status=todos/);
    expect(queryOf(page)).toEqual({ q: tag, categoria: "", status: "todos" });
    await expect(page.getByText("Página 1 de 2")).toBeVisible();
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
    const archivedCategory = await apiCategory(request, ownerToken);
    const archivedProduct = await apiProduct(request, ownerToken, { categoryId: archivedCategory.id });
    await apiArchive(request, ownerToken, "products", archivedProduct.id);
    await apiArchive(request, ownerToken, "categories", archivedCategory.id);

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

    // Archived rows are listed on both pages before checking that no write control exists.
    await page.goto("/produtos?status=todos");
    const row = page.getByRole("row", { name: new RegExp(product.sku) });
    await expect(row).toContainText(category.name);
    const archivedRow = page.getByRole("row", { name: new RegExp(archivedProduct.sku) });
    await expect(archivedRow.getByText("Arquivado")).toBeVisible();
    await expect(archivedRow).toContainText(archivedCategory.name);
    for (const label of ["Novo produto", "Editar", "Arquivar", "Desarquivar"]) {
      await expect(page.getByRole("button", { name: label })).toHaveCount(0);
    }

    await page.goto("/categorias?arquivadas=1");
    await expect(page.getByRole("row", { name: new RegExp(category.name) })).toBeVisible();
    await expect(page.getByRole("row", { name: new RegExp(archivedCategory.name) }).getByText("Arquivada")).toBeVisible();
    for (const label of ["Nova categoria", "Arquivar", "Desarquivar"]) {
      await expect(page.getByRole("button", { name: label })).toHaveCount(0);
    }
  });

  test("never sends a token to the browser", async ({ page, request, context }) => {
    const { token } = await ownerWithToken(page);
    const product = await apiProduct(request, token);
    const before = await cookieValues(page);
    const secrets = [before.access!, before.refresh!];

    // Every response, redirects and errors included, read in the route so no body is lost to a navigation.
    // Set-Cookie is kept apart: the catalog Server Actions must never write a session cookie.
    const captured: string[] = [];
    const actionResponses: { id: string; status: number; setCookies: string[] }[] = [];
    const setCookies: string[] = [];
    const postBodies: string[] = [];
    await page.route("**/*", async (route) => {
      const request = route.request();
      const response = await route.fetch({ maxRedirects: 0 });
      const body = await response.text();
      const headers = response.headersArray();
      const cookies = headers.filter(({ name }) => name.toLowerCase() === "set-cookie").map(({ value }) => value);
      const actionId = request.headers()["next-action"];
      if (actionId) {
        actionResponses.push({ id: actionId, status: response.status(), setCookies: cookies });
        postBodies.push(request.postData() ?? "");
      }
      setCookies.push(...cookies);
      const readable = headers
        .filter(({ name }) => name.toLowerCase() !== "set-cookie")
        .map(({ name, value }) => `${name}: ${value}`);
      captured.push([`${response.status()} ${response.url()}`, ...readable, body].join("\n"));
      await route.fulfill({ response, body });
    });
    const requestUrls: string[] = [];
    page.on("request", (req) => requestUrls.push(req.url()));
    const settle = () => page.waitForLoadState("networkidle");
    const documents: string[] = [];

    // createCategory, archiveCategory, unarchiveCategory.
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

    // createProduct (and a handled duplicate-SKU error), updateProduct, archiveProduct, unarchiveProduct.
    await page.goto("/produtos");
    await page.getByRole("button", { name: "Novo produto" }).click();
    const newSku = uniqueSku();
    await page.getByLabel("SKU", { exact: true }).fill(newSku);
    await page.getByLabel("Nome", { exact: true }).fill(uniqueName("Token novo"));
    await page.getByLabel("Unidade").selectOption({ label: "Unidade (UN)" });
    await page.getByRole("button", { name: "Criar produto" }).click();
    await expect(page.getByRole("row", { name: new RegExp(newSku) })).toBeVisible();

    await page.getByRole("button", { name: "Novo produto" }).click();
    await page.getByLabel("SKU", { exact: true }).fill(product.sku);
    await page.getByLabel("Nome", { exact: true }).fill(uniqueName("Token repetido"));
    await page.getByLabel("Unidade").selectOption({ label: "Unidade (UN)" });
    await page.getByRole("button", { name: "Criar produto" }).click();
    await expect(page.getByText("Já existe um produto com este SKU.")).toBeVisible();
    await page.keyboard.press("Escape");
    await expect(page.getByRole("dialog")).toBeHidden();

    const row = page.getByRole("row", { name: new RegExp(product.sku) });
    await row.getByRole("button", { name: "Editar" }).click();
    await page.getByRole("dialog").getByLabel("Nome", { exact: true }).fill(uniqueName("Token edit"));
    await page.getByRole("dialog").getByRole("button", { name: "Salvar" }).click();
    await expect(page.getByRole("dialog")).toBeHidden();
    await row.getByRole("button", { name: "Arquivar" }).click();
    await confirmArchive(page);
    await filter(page, { status: "Arquivados" });
    await row.getByRole("button", { name: "Desarquivar" }).click();
    await expect(row).toHaveCount(0);
    await settle();
    documents.push(await page.content());

    // The seven distinct catalog actions ran; none of them wrote a cookie.
    expect(new Set(actionResponses.map((r) => r.id)).size).toBe(7);
    expect(actionResponses.length).toBeGreaterThanOrEqual(8);
    for (const { setCookies: written } of actionResponses) expect(written).toEqual([]);
    expect(await cookieValues(page)).toEqual(before);

    // Session expiry: a rejected access token sends the action to /auth/expired, then /entrar.
    await page.goto("/categorias");
    await settle();
    const [access] = (await context.cookies()).filter((c) => c.name === "atlas_access");
    await context.addCookies([{ ...access, value: "not-a-valid-token" }]);
    const expiring = uniqueName("Expira");
    await page.getByRole("button", { name: "Nova categoria" }).click();
    await page.getByLabel("Nome", { exact: true }).fill(expiring);
    await page.getByRole("button", { name: "Criar categoria" }).click();
    await expect(page).toHaveURL(/\/entrar/);
    documents.push(await page.content());
    expect(actionResponses.at(-1)!.setCookies).toEqual([]);

    // Bodies, headers (Set-Cookie aside), URLs, posted action bodies and pages: no token anywhere.
    const texts = [...captured, ...documents, ...requestUrls, ...postBodies];
    expect(texts.length).toBeGreaterThan(5);
    for (const text of texts) {
      for (const secret of secrets) expect(text).not.toContain(secret);
    }
    // Only auth routes may set cookies; whatever they set was a session cookie and never reached the DOM.
    for (const cookie of setCookies) expect(cookie).toMatch(/^atlas_(access|refresh)=/);
    expect(await page.evaluate(() => document.cookie)).not.toContain("atlas_");
    expect(requestUrls.filter((url) => url.startsWith(API))).toEqual([]);
  });

  test("has no page-level horizontal scroll at 375 px", async ({ page, request }) => {
    await page.setViewportSize(MOBILE);
    const { token } = await ownerWithToken(page);
    const category = await apiCategory(request, token, "C".repeat(80));
    const product = await apiProduct(request, token, {
      sku: "S".repeat(40),
      name: "W".repeat(200),
      categoryId: category.id,
    });

    // A deliberately wide table: its wrapper must be the one that scrolls, never the document.
    const check = async () => {
      await page.addStyleTag({ content: '[data-slot="table"] { min-width: 900px; }' });
      expect(await hasHorizontalScroll(page)).toBe(false);
      const [container] = await tableContainers(page);
      expect(container.overflowX).toBe("auto");
      expect(container.scrollWidth).toBeGreaterThan(container.clientWidth);
      const scrolled = await page.evaluate(() => {
        const el = document.querySelector('[data-slot="table-container"]')!;
        el.scrollLeft = 100;
        return el.scrollLeft;
      });
      expect(scrolled).toBeGreaterThan(0);
      expect(await hasHorizontalScroll(page)).toBe(false);
    };

    await page.goto("/categorias");
    await expect(page.getByRole("row", { name: new RegExp(category.name) })).toBeVisible();
    await check();

    await page.goto("/produtos");
    await expect(page.getByRole("row", { name: new RegExp(product.sku) })).toBeVisible();
    await check();

    // Real long values alone: still no page-level scroll (the wrapper takes any overflow).
    await page.goto("/produtos");
    await expect(page.getByRole("row", { name: new RegExp(product.sku) })).toBeVisible();
    expect(await hasHorizontalScroll(page)).toBe(false);
  });

  test("shows every products column at 1280 px", async ({ page, request }) => {
    await page.setViewportSize(DESKTOP);
    const { token } = await ownerWithToken(page);
    const category = await apiCategory(request, token);
    const longName = "W".repeat(200);
    const product = await apiProduct(request, token, { name: longName, categoryId: category.id });

    await page.goto("/produtos");
    const row = page.getByRole("row", { name: new RegExp(product.sku) });
    await expect(row).toBeVisible();
    for (const column of ["SKU", "Nome", "Categoria", "Unidade", "Status", "Ações"]) {
      const header = page.getByRole("columnheader", { name: column });
      await expect(header).toBeVisible();
      const box = (await header.boundingBox())!;
      expect(box.x).toBeGreaterThanOrEqual(0);
      expect(box.x + box.width).toBeLessThanOrEqual(DESKTOP.width);
    }

    // The whole 200-character name is rendered, and its text (wrapped or not) stays inside its cell.
    const cell = row.getByRole("cell").nth(1);
    await expect(cell).toHaveText(longName);
    const geometry = await cell.evaluate((el) => {
      const range = document.createRange();
      range.selectNodeContents(el);
      const text = range.getBoundingClientRect();
      const box = el.getBoundingClientRect();
      return {
        text: { left: text.left, right: text.right },
        cell: { left: box.left, right: box.right },
        scrollWidth: el.scrollWidth,
        clientWidth: el.clientWidth,
      };
    });
    expect(geometry.text.left).toBeGreaterThanOrEqual(geometry.cell.left - 0.5);
    expect(geometry.text.right).toBeLessThanOrEqual(geometry.cell.right + 0.5);
    expect(geometry.text.right).toBeLessThanOrEqual(DESKTOP.width);
    expect(geometry.scrollWidth).toBeLessThanOrEqual(geometry.clientWidth);

    const containers = await tableContainers(page);
    expect(containers).toHaveLength(1);
    for (const c of containers) expect(c.scrollWidth).toBeLessThanOrEqual(c.clientWidth);
    expect(await hasHorizontalScroll(page)).toBe(false);
  });
});
