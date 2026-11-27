import { screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("server-only", () => ({}));
vi.mock("@/lib/company-actions", () => ({ createCompany: vi.fn() }));

const getCompanies = vi.fn();
const getMe = vi.fn();
vi.mock("@/lib/api", () => ({
  getCompanies: (...args: unknown[]) => getCompanies(...args),
  getMe: () => getMe(),
}));

import { renderServer } from "@/test/render-server";

import CompaniesPage from "./page";

const company = { id: "c1", name: "Acme", cnpj: null, status: "ACTIVE" };

async function renderPage(searchParams: Record<string, string | string[]>) {
  await renderServer(<CompaniesPage searchParams={Promise.resolve(searchParams) as never} />);
}

beforeEach(() => {
  getCompanies.mockReset().mockResolvedValue([company]);
  getMe.mockReset().mockResolvedValue({ role: "OWNER" });
});

describe("CompaniesPage archived filter", () => {
  it.each([
    ["is absent", {}],
    ["has an invalid value", { arquivadas: "true" }],
    ["is empty", { arquivadas: "" }],
    ["is 0", { arquivadas: "0" }],
    ["is repeated", { arquivadas: ["1", "1"] }],
    ["sits beside unrelated keys", { arquivados: "1", foo: "1" }],
  ])("requests active companies when the filter %s", async (_label, params) => {
    await renderPage(params);
    expect(getCompanies).toHaveBeenCalledExactlyOnceWith(false);
    const filter = screen.getByRole("link", { name: "Mostrar arquivadas" });
    expect(filter).toHaveAttribute("aria-pressed", "false");
    expect(filter).toHaveAttribute("href", "/empresas?arquivadas=1");
  });

  it("requests archived companies for exactly 1, ignoring unrelated keys", async () => {
    await renderPage({ arquivadas: "1", foo: "bar" });
    expect(getCompanies).toHaveBeenCalledExactlyOnceWith(true);
    const filter = screen.getByRole("link", { name: "Mostrar arquivadas" });
    expect(filter).toHaveAttribute("aria-pressed", "true");
    expect(filter).toHaveAttribute("href", "/empresas");
  });
});

describe("CompaniesPage role wiring", () => {
  it.each([
    ["OWNER", true],
    ["ADMIN", true],
    ["MEMBER", false],
  ])("%s sees the create button: %s", async (role, visible) => {
    getMe.mockResolvedValue({ role });
    await renderPage({});
    expect(screen.getByRole("heading", { name: "Empresas" })).toBeInTheDocument();
    if (visible) {
      expect(screen.getByRole("button", { name: "Nova empresa" })).toBeInTheDocument();
    } else {
      expect(screen.queryByRole("button", { name: "Nova empresa" })).not.toBeInTheDocument();
    }
  });
});
