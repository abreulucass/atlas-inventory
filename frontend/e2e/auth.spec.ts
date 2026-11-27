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
  test("signs out and protects the home page again", async ({ page, request }) => {
    await register(page);
    const { refresh } = await cookieValues(page);
    expect(refresh).toBeTruthy();

    await signOut(page);
    // The backend revoked the session, not only the local cookies (LOG-05).
    const revoked = await request.post("http://localhost:8080/api/auth/refresh", {
      data: { refreshToken: refresh },
    });
    expect(revoked.status()).toBe(401);
    expect((await cookieValues(page)).access).toBeUndefined();
    expect((await cookieValues(page)).refresh).toBeUndefined();

    await page.goto("/");
    await expect(page).toHaveURL("/entrar?next=%2F");
  });
});

test.describe("session", () => {
  test("never exposes the tokens to the page", async ({ page, context }) => {
    type Captured = { method: string; path: string; status: number; setsSession: boolean; text: string };
    const pending: Promise<Captured>[] = [];
    page.on("response", (response) => {
      pending.push(
        (async () => {
          const all = await response.allHeaders();
          const headers = Object.entries(all)
            .filter(([name]) => name !== "set-cookie")
            .map(([name, value]) => `${name}: ${value}`);
          // Redirects have no body; RSC payloads and documents do.
          const body = await response.text().catch(() => "");
          return {
            method: response.request().method(),
            path: new URL(response.url()).pathname,
            status: response.status(),
            setsSession: (all["set-cookie"] ?? "").includes("atlas_refresh="),
            text: [...headers, body].join("\n"),
          };
        })(),
      );
    });
    const pairs: { access?: string; refresh?: string }[] = [];
    const issued = async () => {
      const pair = await cookieValues(page);
      expect(pair.access).toBeTruthy();
      expect(pair.refresh).toBeTruthy();
      pairs.push(pair);
    };

    const user = await register(page);
    await issued();
    await context.clearCookies();
    await page.goto("/entrar");
    await fillLogin(page, user.email, user.password);
    await expect(page).toHaveURL("/");
    await expect(page.getByRole("heading", { name: `Olá, ${user.name}` })).toBeVisible();
    await issued();
    await context.clearCookies({ name: ACCESS_COOKIE });
    await page.reload();
    await expect(page.getByRole("heading", { name: `Olá, ${user.name}` })).toBeVisible();
    await issued();

    const captured = await Promise.all(pending);
    const found = (method: string, path: string) =>
      captured.some((c) => c.method === method && c.path === path && c.setsSession);
    expect(found("POST", "/cadastro"), "register response captured").toBe(true);
    expect(found("POST", "/entrar"), "login response captured").toBe(true);
    expect(found("GET", "/"), "refresh response captured").toBe(true);
    expect(captured.some((c) => (c.status >= 300 && c.status < 400) || c.text.includes("x-action-redirect"))).toBe(true);
    expect(new Set(pairs.map((p) => p.refresh)).size).toBe(3);
    for (const { access, refresh } of pairs) {
      for (const { text } of captured) {
        expect(text).not.toContain(access!);
        expect(text).not.toContain(refresh!);
      }
    }
    const documentCookie = await page.evaluate(() => document.cookie);
    for (const { access, refresh } of pairs) {
      expect(documentCookie).not.toContain(access!);
      expect(documentCookie).not.toContain(refresh!);
    }
    expect(documentCookie).not.toContain("atlas_");
  });

  test("redirects anonymous visitors to the login page with next", async ({ page }) => {
    await page.goto("/");

    await expect(page).toHaveURL("/entrar?next=%2F");

    await page.goto("/rota/protegida?x=1&y=2");
    await expect(page).toHaveURL(/\/entrar\?next=/);
    expect(new URL(page.url()).pathname).toBe("/entrar");
    expect(new URL(page.url()).searchParams.get("next")).toBe("/rota/protegida?x=1&y=2");
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
    expect(after.refresh).toBeTruthy();
    expect(after.refresh).not.toBe(before.refresh);

    // The rotated pair refreshes again.
    await context.clearCookies({ name: ACCESS_COOKIE });
    await page.reload();
    await expect(page).toHaveURL("/");
    await expect(page.getByRole("heading", { name: `Olá, ${user.name}` })).toBeVisible();
    const again = await cookieValues(page);
    expect(again.access).toBeTruthy();
    expect(again.refresh).toBeTruthy();
    expect(again.refresh).not.toBe(after.refresh);
  });

  test("refuses actions from another origin", async ({ page, context, request }) => {
    // SPEC_DEVIATION: Chromium ignores an Origin override in route.continue (the wire keeps the
    // app's origin), so the intercepted POST is replayed with route.fetch, which sends the header.
    const forge = async (path: string) => {
      const refused: { status: number; headers: Record<string, string> }[] = [];
      await page.route(path, async (route) => {
        if (route.request().method() !== "POST") return route.continue();
        const response = await route.fetch({
          headers: { ...route.request().headers(), origin: "https://evil.example" },
        });
        refused.push({ status: response.status(), headers: response.headers() });
        await route.fulfill({ response });
      });
      return refused;
    };
    const expectRefused = async (refused: { status: number; headers: Record<string, string> }[]) => {
      await expect.poll(() => refused.length).toBe(1);
      expect(refused[0].status).toBe(403);
      expect(refused[0].headers["set-cookie"]).toBeUndefined();
      expect(refused[0].headers["x-action-redirect"]).toBeUndefined();
    };

    // Login.
    const user = await register(page);
    await context.clearCookies();
    const login = await forge("**/entrar");
    await page.goto("/entrar");
    await fillLogin(page, user.email, user.password);
    await expectRefused(login);
    await page.waitForTimeout(500);
    await expect(page).toHaveURL("/entrar");
    expect((await cookieValues(page)).access).toBeUndefined();
    expect((await cookieValues(page)).refresh).toBeUndefined();
    await page.unrouteAll({ behavior: "wait" });

    // Register: refused, and the same e-mail can still be registered normally.
    const fresh = newUser();
    const signUp = await forge("**/cadastro");
    await page.goto("/cadastro");
    await fillRegistration(page, fresh);
    await expectRefused(signUp);
    await page.waitForTimeout(500);
    await expect(page).toHaveURL("/cadastro");
    expect((await cookieValues(page)).access).toBeUndefined();
    await page.unrouteAll({ behavior: "wait" });
    await register(page, fresh);

    // Logout: refused, and the session's refresh token still works at the backend.
    const { refresh } = await cookieValues(page);
    const signOutForged = await forge("http://localhost:3000/");
    await page.getByRole("button", { name: /Ana / }).click();
    await page.getByRole("menuitem", { name: "Sair" }).click();
    await expectRefused(signOutForged);
    await page.unrouteAll({ behavior: "wait" });
    const backend = await request.post("http://localhost:8080/api/auth/refresh", {
      data: { refreshToken: refresh },
    });
    expect(backend.status()).toBe(200);
  });

  test("keeps the session and shows the unavailable page when the refresh is unavailable", async ({
    page,
    context,
  }) => {
    // Port 3001 runs the same build against a backend whose /api/auth/refresh answers 503.
    const app = "http://localhost:3001";
    const user = newUser();
    await page.goto(`${app}/cadastro`);
    await fillRegistration(page, user);
    await expect(page).toHaveURL(`${app}/`);
    const before = await cookieValues(page);

    await context.clearCookies({ name: ACCESS_COOKIE });
    await page.reload();

    await expect(page).toHaveURL(`${app}/`);
    await expect(page.getByText("Serviço indisponível. Tente novamente em instantes.")).toBeVisible();
    const after = await cookieValues(page);
    expect(after.refresh).toBe(before.refresh);
    expect(after.access).toBeUndefined();
  });
});
