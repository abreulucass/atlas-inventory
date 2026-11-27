import { expect, test, type APIRequestContext, type Cookie, type Page } from "@playwright/test";

import { cookieValues, fillLogin, newUser, register, signOut, type TestUser } from "./helpers";

const API = "http://localhost:8080";
const DESKTOP = { width: 1280, height: 800 };
const MOBILE = { width: 375, height: 800 };

// User B owns org B (registered through the API) and adds `member` to it as ADMIN.
async function otherOrganizationWith(request: APIRequestContext, member: TestUser, orgName: string) {
  const owner = newUser();
  const registered = await request.post(`${API}/api/auth/register`, {
    data: {
      name: owner.name,
      email: owner.email,
      password: owner.password,
      organizationName: orgName,
      organizationSlug: owner.slug,
    },
  });
  expect(registered.status()).toBe(201);
  const { accessToken } = await registered.json();
  const added = await request.post(`${API}/api/members`, {
    headers: { Authorization: `Bearer ${accessToken}` },
    data: { email: member.email, role: "ADMIN" },
  });
  expect(added.ok()).toBe(true);
  return { ownerToken: accessToken as string };
}

// The other organization's owner removes `member` from it.
async function removeMember(request: APIRequestContext, ownerToken: string, member: TestUser) {
  const headers = { Authorization: `Bearer ${ownerToken}` };
  const members = await (await request.get(`${API}/api/members`, { headers })).json();
  const userId = members.find((m: { email: string }) => m.email === member.email).userId;
  const removed = await request.delete(`${API}/api/members/${userId}`, { headers });
  expect(removed.ok()).toBe(true);
}

const POLICY = ["httpOnly", "sameSite", "path", "secure"] as const;
const policyOf = (cookie: Cookie | undefined) =>
  Object.fromEntries(POLICY.map((k) => [k, cookie?.[k]]));

// Registers A (org A), gives A a second membership, and reloads so the header lists both.
async function userWithTwoOrganizations(
  page: Page,
  request: APIRequestContext,
  otherName = `Outra ${newUser().slug}`,
) {
  const user = await register(page);
  const { ownerToken } = await otherOrganizationWith(request, user, otherName);
  await page.reload();
  return { user, otherName, ownerToken };
}

const switchTo = async (page: Page, name: string) => {
  await page.getByRole("button", { name: /^Org / }).click();
  await page.getByRole("menuitem", { name }).click();
};

test.describe("organizations", () => {
  test("shows the active organization in the header", async ({ page }) => {
    const user = await register(page);
    await expect(page.locator("header").getByText(user.organizationName)).toBeVisible();

    await page.reload();
    await expect(page.locator("header").getByText(user.organizationName)).toBeVisible();
  });

  test("switches and lands home in the other organization", async ({ page, request }) => {
    const { user, otherName } = await userWithTwoOrganizations(page, request);
    await page.goto("/?from=test");
    await expect(page).toHaveURL("/?from=test");
    await switchTo(page, otherName);

    await expect(page).toHaveURL("/");
    await expect(page.getByRole("button", { name: otherName })).toBeVisible();
    await expect(page.getByRole("heading", { name: `Olá, ${user.name}` })).toBeVisible();
  });

  test("shows the role held in the new organization", async ({ page, request }) => {
    const { user, otherName } = await userWithTwoOrganizations(page, request);
    await switchTo(page, otherName);
    await expect(page.getByRole("button", { name: otherName })).toBeVisible();

    await page.getByRole("button", { name: user.name }).click();
    const menu = page.getByRole("menu");
    await expect(menu.getByText("Administrador")).toBeVisible();
    await expect(menu.getByText("Proprietário")).toBeHidden();
  });

  test("never exposes the switched tokens to the page", async ({ page, request }) => {
    const { user, otherName } = await userWithTwoOrganizations(page, request);
    // a real login, so the replacement cookies can be compared with the ones login sets
    await signOut(page);
    await fillLogin(page, user.email, user.password);
    await expect(page).toHaveURL("/");
    const before = await cookieValues(page);
    const loginCookies = await page.context().cookies();
    const loginPolicy = {
      access: policyOf(loginCookies.find((c) => c.name === "atlas_access")),
      refresh: policyOf(loginCookies.find((c) => c.name === "atlas_refresh")),
    };
    expect(loginPolicy.access.httpOnly).toBe(true);
    expect(loginPolicy.refresh.httpOnly).toBe(true);

    const captured: { action: boolean; text: Promise<string> }[] = [];
    page.on("response", (response) => {
      if (response.status() >= 300 && response.status() < 400) return;
      const headers = response.request().headers();
      captured.push({
        action: response.request().method() === "POST" && "next-action" in headers,
        text: (async () => {
          const names = Object.entries(await response.allHeaders())
            .filter(([name]) => name !== "set-cookie")
            .map(([name, value]) => `${name}: ${value}`);
          return [...names, await response.text()].join("\n");
        })(),
      });
    });
    await switchTo(page, otherName);
    await expect(page.getByRole("button", { name: otherName })).toBeVisible();

    const after = await cookieValues(page);
    expect(after.access).toBeTruthy();
    expect(after.refresh).toBeTruthy();
    expect(after.access).not.toBe(before.access);
    expect(after.refresh).not.toBe(before.refresh);
    const cookies = await page.context().cookies();
    for (const [key, name] of [
      ["access", "atlas_access"],
      ["refresh", "atlas_refresh"],
    ] as const) {
      expect(policyOf(cookies.find((c) => c.name === name))).toEqual(loginPolicy[key]);
    }
    expect(captured.some((c) => c.action)).toBe(true);
    // every body must be read; an unreadable one fails the test instead of passing as clean
    const texts = await Promise.all(captured.map((c) => c.text));
    expect(texts.length).toBeGreaterThan(0);
    for (const text of texts) {
      expect(text).not.toContain(after.access!);
      expect(text).not.toContain(after.refresh!);
      expect(text).not.toContain(before.access!);
      expect(text).not.toContain(before.refresh!);
    }
    expect(await page.evaluate(() => document.cookie)).not.toContain("atlas_");
  });

  test("truncates the name without horizontal scroll at 375 px", async ({ page, request }) => {
    await page.setViewportSize(MOBILE);
    const longName = `Organização com um nome extremamente longo ${"x".repeat(60)}`;
    const user = await register(page);
    await otherOrganizationWith(request, user, longName);
    await page.reload();

    const trigger = page.getByRole("button", { name: /^Org / });
    await expect(trigger).toBeVisible();
    await switchTo(page, longName);
    const longTrigger = page.getByRole("button", { name: longName });
    await expect(longTrigger).toBeVisible();

    const box = await longTrigger.boundingBox();
    expect(box!.width).toBeLessThan(MOBILE.width / 2 + 40);
    const label = await longTrigger
      .locator("span")
      .first()
      .evaluate((el) => {
        const style = getComputedStyle(el);
        return {
          clipped: el.scrollWidth > el.clientWidth,
          overflow: style.overflow,
          whiteSpace: style.whiteSpace,
          textOverflow: style.textOverflow,
        };
      });
    expect(label).toEqual({
      clipped: true,
      overflow: "hidden",
      whiteSpace: "nowrap",
      textOverflow: "ellipsis",
    });
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth),
    ).toBe(false);
  });

  test("keeps the header on one line at 1280 px", async ({ page, request }) => {
    await page.setViewportSize(DESKTOP);
    const { user } = await userWithTwoOrganizations(page, request);

    const centers = await Promise.all(
      [
        page.getByRole("button", { name: /^Org / }),
        page.getByRole("button", { name: "Alterar tema" }),
        page.getByRole("button", { name: user.name }),
      ].map(async (locator) => {
        await expect(locator).toBeVisible();
        const box = (await locator.boundingBox())!;
        return box.y + box.height / 2;
      }),
    );
    expect(Math.max(...centers) - Math.min(...centers)).toBeLessThan(4);
  });

  test("shows the membership error in full at 375 px when the membership is gone", async ({
    page,
    request,
  }) => {
    await page.setViewportSize(MOBILE);
    const { user, otherName, ownerToken } = await userWithTwoOrganizations(page, request);
    const before = await cookieValues(page);
    await removeMember(request, ownerToken, user);

    await switchTo(page, otherName);
    const alert = page.locator("header [role=alert]");
    await expect(alert).toHaveText("Você não faz mais parte desta organização.");
    await expect(alert).toBeVisible();
    const box = (await alert.boundingBox())!;
    expect(box.width).toBeGreaterThan(0);
    expect(box.x).toBeGreaterThanOrEqual(0);
    expect(box.x + box.width).toBeLessThanOrEqual(MOBILE.width);
    expect(await alert.evaluate((el) => el.scrollWidth <= el.clientWidth)).toBe(true);
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth),
    ).toBe(false);
    expect(await cookieValues(page)).toEqual(before);
  });

  test("closes the menu when the active organization is selected", async ({ page, request }) => {
    await userWithTwoOrganizations(page, request);
    const trigger = page.getByRole("button", { name: /^Org / });
    await trigger.click();
    await page.getByRole("menuitem", { name: /^Org / }).click();
    await expect(page.getByRole("menu")).toBeHidden();
    await expect(trigger).toBeFocused();
    await expect(page).toHaveURL("/");
  });
});
