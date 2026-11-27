import { expect, test, type Page } from "@playwright/test";

import { register } from "./helpers";

const DESKTOP = { width: 1280, height: 800 };
const MOBILE = { width: 375, height: 800 };

const hasHorizontalScroll = (page: Page) =>
  page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth);

test.describe("shell", () => {
  test("shows the greeting and the sidebar", async ({ page }) => {
    await page.setViewportSize(DESKTOP);
    const user = await register(page);

    await expect(page.getByRole("heading", { name: `Olá, ${user.name}` })).toBeVisible();
    await expect(page.getByText("Atlas Inventory")).toBeVisible();
    await expect(page.getByRole("link", { name: "Início" })).toBeVisible();

    await page.getByRole("button", { name: user.name }).click();
    await expect(page.getByText(user.email)).toBeVisible();
    await expect(page.getByText("Proprietário")).toBeVisible();
  });

  test("keeps the chosen theme after a reload", async ({ page }) => {
    await page.setViewportSize(DESKTOP);
    await register(page);

    await page.getByRole("button", { name: "Alterar tema" }).click();
    await page.getByRole("menuitem", { name: "Escuro" }).click();
    await expect(page.locator("html")).toHaveClass(/dark/);

    await page.reload();
    await expect(page.locator("html")).toHaveClass(/dark/);
  });

  test("opens the sidebar as a sheet on narrow screens", async ({ page }) => {
    await page.setViewportSize(MOBILE);
    const user = await register(page);

    // SHL-02 at 375 px: the user menu shows who is signed in and the role.
    await page.getByRole("button", { name: user.name }).click();
    const menu = page.getByRole("menu");
    await expect(menu.getByText(user.name)).toBeVisible();
    await expect(menu.getByText(user.email)).toBeVisible();
    await expect(menu.getByText("Proprietário")).toBeVisible();
    await page.keyboard.press("Escape");
    await expect(menu).toBeHidden();

    await expect(page.getByRole("link", { name: "Início" })).toBeHidden();
    await page.getByRole("button", { name: "Abrir menu" }).click();

    const sheet = page.getByRole("dialog");
    await expect(sheet).toBeVisible();
    await expect(sheet.getByText("Atlas Inventory")).toBeVisible();
    await expect(sheet.getByRole("link", { name: "Início" })).toBeVisible();
  });

  test("has no horizontal scroll at 375 and 1280 px", async ({ page }) => {
    for (const size of [MOBILE, DESKTOP]) {
      await page.setViewportSize(size);
      for (const [path, submit] of [
        ["/entrar", "Entrar"],
        ["/cadastro", "Criar conta"],
      ]) {
        await page.goto(path);
        await expect(page).toHaveURL(path);
        await expect(page.getByRole("button", { name: submit, exact: true })).toBeVisible();
        expect(await hasHorizontalScroll(page), `${path} at ${size.width}px`).toBe(false);
      }
    }

    const user = await register(page);
    for (const size of [MOBILE, DESKTOP]) {
      await page.setViewportSize(size);
      await page.goto("/");
      await expect(page).toHaveURL("/");
      await expect(page.getByRole("heading", { name: `Olá, ${user.name}` })).toBeVisible();
      expect(await hasHorizontalScroll(page), `/ at ${size.width}px`).toBe(false);
    }
  });
});
