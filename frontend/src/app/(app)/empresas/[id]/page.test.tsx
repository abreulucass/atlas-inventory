import { screen, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("server-only", () => ({}));
vi.mock("@/lib/company-actions", () => ({
  archiveCompany: vi.fn(),
  unarchiveCompany: vi.fn(),
  archiveWarehouse: vi.fn(),
  unarchiveWarehouse: vi.fn(),
  createWarehouse: vi.fn(),
}));

const getCompany = vi.fn();
const getWarehouses = vi.fn();
const getMe = vi.fn();
vi.mock("@/lib/api", () => ({
  getCompany: (...args: unknown[]) => getCompany(...args),
  getWarehouses: (...args: unknown[]) => getWarehouses(...args),
  getMe: () => getMe(),
}));

import { renderServer } from "@/test/render-server";

import CompanyPage from "./page";

const active = { id: "c1", name: "Acme", cnpj: null, status: "ACTIVE" };
const archived = { ...active, status: "ARCHIVED" };
const rows = [
  { id: "w1", companyId: "c1", name: "Central", code: "CD-1", status: "ACTIVE" },
  { id: "w2", companyId: "c1", name: "Antigo", code: "CD-2", status: "ARCHIVED" },
];

async function renderPage(searchParams: Record<string, string | string[]> = {}) {
  await renderServer(
    <CompanyPage
      params={Promise.resolve({ id: "c1" })}
      searchParams={Promise.resolve(searchParams) as never}
    />,
  );
}

beforeEach(() => {
  getCompany.mockReset().mockResolvedValue(active);
  getWarehouses.mockReset().mockResolvedValue(rows);
  getMe.mockReset().mockResolvedValue({ role: "OWNER" });
});

describe("CompanyPage archived filter", () => {
  it.each([
    ["is absent", {}],
    ["has an invalid value", { arquivados: "true" }],
    ["is 0", { arquivados: "0" }],
    ["is repeated", { arquivados: ["1", "1"] }],
    ["sits beside unrelated keys", { arquivadas: "1", foo: "1" }],
  ])("requests active warehouses when the filter %s", async (_label, params) => {
    await renderPage(params);
    expect(getWarehouses).toHaveBeenCalledExactlyOnceWith("c1", false);
    const filter = screen.getByRole("link", { name: "Mostrar arquivados" });
    expect(filter).toHaveAttribute("aria-pressed", "false");
    expect(filter).toHaveAttribute("href", "/empresas/c1?arquivados=1");
  });

  it("requests archived warehouses for exactly 1, ignoring unrelated keys", async () => {
    await renderPage({ arquivados: "1", foo: "bar" });
    expect(getWarehouses).toHaveBeenCalledExactlyOnceWith("c1", true);
    const filter = screen.getByRole("link", { name: "Mostrar arquivados" });
    expect(filter).toHaveAttribute("aria-pressed", "true");
    expect(filter).toHaveAttribute("href", "/empresas/c1");
  });
});

describe("CompanyPage role wiring", () => {
  it.each([
    ["OWNER", true],
    ["ADMIN", true],
    ["MEMBER", false],
  ])("%s on an active company: write controls %s", async (role, manager) => {
    getMe.mockResolvedValue({ role });
    await renderPage({ arquivados: "1" });
    const [, activeRow, archivedRow] = screen.getAllByRole("row");
    const has = (name: string) => screen.queryByRole("button", { name }) !== null;
    expect(has("Novo depósito")).toBe(manager);
    expect(has("Desarquivar")).toBe(manager);
    expect(within(activeRow).queryByRole("button", { name: "Arquivar" }) !== null).toBe(manager);
    expect(within(archivedRow).queryByRole("button", { name: "Desarquivar" }) !== null).toBe(manager);
    // company control + the active warehouse row
    expect(screen.queryAllByRole("button", { name: "Arquivar" })).toHaveLength(manager ? 2 : 0);
  });

  it.each([
    ["OWNER", true],
    ["ADMIN", true],
    ["MEMBER", false],
  ])("%s on an archived company: write controls %s", async (role, manager) => {
    getCompany.mockResolvedValue(archived);
    getMe.mockResolvedValue({ role });
    await renderPage({ arquivados: "1" });
    expect(screen.queryByRole("button", { name: "Novo depósito" })).not.toBeInTheDocument();
    // company Desarquivar + archived warehouse row Desarquivar
    expect(screen.queryAllByRole("button", { name: "Desarquivar" })).toHaveLength(manager ? 2 : 0);
    expect(screen.queryAllByRole("button", { name: "Arquivar" })).toHaveLength(manager ? 1 : 0);
  });
});
