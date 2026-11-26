import { expect, test } from "@playwright/test";

import {
  ACCESS_COOKIE,
  cookieValues,
  fillLogin,
  fillRegistration,
  newUser,
  register,
  signOut,
} from "./helpers";

test.describe("registration", () => {
  test("creates the account and lands on the home page", async ({ page }) => {
    const user = await register(page);

    await expect(page.getByRole("heading", { name: `Olá, ${user.name}` })).toBeVisible();
    expect((await cookieValues(page)).access).toBeTruthy();
  });

  test("shows the duplicate e-mail message", async ({ page, context }) => {
    const user = await register(page);
    await context.clearCookies();

    await page.goto("/cadastro");
    await fillRegistration(page, { ...newUser(), email: user.email });

    await expect(page.getByText("Este e-mail já está cadastrado.")).toBeVisible();
    await expect(page.getByLabel("E-mail")).toHaveAccessibleDescription(
      "Este e-mail já está cadastrado.",
    );
  });

  test("shows the duplicate slug message", async ({ page, context }) => {
    const user = await register(page);
    await context.clearCookies();

    await page.goto("/cadastro");
    await fillRegistration(page, { ...newUser(), slug: user.slug });

    await expect(page.getByText("Este endereço já está em uso.")).toBeVisible();
    await expect(page.getByLabel("Endereço da organização")).toHaveAccessibleDescription(
      "Este endereço já está em uso.",
    );
  });
});

test.describe("login", () => {
  test("redirects to the next path after signing in", async ({ page, context }) => {
    const user = await register(page);
    await context.clearCookies();

    await page.goto("/entrar?next=%2Fnao-existe");
    await fillLogin(page, user.email, user.password);
    await expect(page).toHaveURL("/nao-existe");

    await context.clearCookies();
    await page.goto("/entrar?next=https%3A%2F%2Fevil.example");
    await fillLogin(page, user.email, user.password);
    await expect(page).toHaveURL("/");
  });

  test("shows the invalid credentials message", async ({ page, context }) => {
    const user = await register(page);
    await context.clearCookies();

    await page.goto("/entrar");
    await fillLogin(page, user.email, "senha-errada-123");

    await expect(page.getByText("E-mail ou senha inválidos.")).toBeVisible();
    await expect(page.getByLabel("E-mail")).toHaveValue(user.email);
    await expect(page.getByLabel("Senha")).toHaveValue("");
  });
});

test.describe("logout", () => {
  test("signs out and protects the home page again", async ({ page }) => {
    await register(page);

    await signOut(page);
    expect((await cookieValues(page)).access).toBeUndefined();
    expect((await cookieValues(page)).refresh).toBeUndefined();

    await page.goto("/");
    await expect(page).toHaveURL("/entrar?next=%2F");
  });
});

test.describe("session", () => {
  test("never exposes the tokens to the page", async ({ page, context }) => {
    const exposed: string[] = [];
    page.on("response", async (response) => {
      const headers = Object.entries(await response.allHeaders())
        .filter(([name]) => name !== "set-cookie")
        .map(([name, value]) => `${name}: ${value}`);
      const body = await response.text().catch(() => "");
      exposed.push(...headers, body);
    });

    const user = await register(page);
    await context.clearCookies();
    await page.goto("/entrar");
    await fillLogin(page, user.email, user.password);
    await expect(page).toHaveURL("/");
    await expect(page.getByRole("heading", { name: `Olá, ${user.name}` })).toBeVisible();

    const { access, refresh } = await cookieValues(page);
    expect(access).toBeTruthy();
    expect(refresh).toBeTruthy();
    expect(exposed.length).toBeGreaterThan(0);
    for (const text of exposed) {
      expect(text).not.toContain(access!);
      expect(text).not.toContain(refresh!);
    }
    const documentCookie = await page.evaluate(() => document.cookie);
    expect(documentCookie).not.toContain(access!);
    expect(documentCookie).not.toContain(refresh!);
    expect(documentCookie).not.toContain("atlas_");
  });

  test("redirects anonymous visitors to the login page with next", async ({ page }) => {
    await page.goto("/");

    await expect(page).toHaveURL("/entrar?next=%2F");
  });

  test("redirects signed-in users away from the auth pages", async ({ page }) => {
    await register(page);

    await page.goto("/entrar");
    await expect(page).toHaveURL("/");
    await page.goto("/cadastro");
    await expect(page).toHaveURL("/");
  });

  test("refreshes an expired access token transparently", async ({ page, context }) => {
    const user = await register(page);
    const before = await cookieValues(page);

    await context.clearCookies({ name: ACCESS_COOKIE });
    await page.reload();

    await expect(page).toHaveURL("/");
    await expect(page.getByRole("heading", { name: `Olá, ${user.name}` })).toBeVisible();
    const after = await cookieValues(page);
    expect(after.access).toBeTruthy();
    expect(after.refresh).not.toBe(before.refresh);
  });

  test("refuses actions from another origin", async ({ page, context }) => {
    const user = await register(page);
    await context.clearCookies();

    // SPEC_DEVIATION: Chromium ignores an Origin override in route.continue (the wire keeps the
    // app's origin), so the intercepted POST is replayed with route.fetch, which sends the header.
    const refused: { status: number; headers: Record<string, string> }[] = [];
    await page.route("**/entrar", async (route) => {
      if (route.request().method() !== "POST") return route.continue();
      const response = await route.fetch({
        headers: { ...route.request().headers(), origin: "https://evil.example" },
      });
      refused.push({ status: response.status(), headers: response.headers() });
      await route.fulfill({ response });
    });

    await page.goto("/entrar");
    await fillLogin(page, user.email, user.password);
    await expect.poll(() => refused.length).toBe(1);

    expect(refused[0].status).toBe(500);
    expect(refused[0].headers["set-cookie"]).toBeUndefined();
    expect(refused[0].headers["x-action-redirect"]).toBeUndefined();
    await page.waitForTimeout(500);
    await expect(page).toHaveURL("/entrar");
    expect((await cookieValues(page)).access).toBeUndefined();
    expect((await cookieValues(page)).refresh).toBeUndefined();
  });
});
