import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("server-only", () => ({}));

const jar = new Map<string, string>();
const writes: string[] = [];
vi.mock("next/headers", () => ({
  cookies: async () => ({
    get: (name: string) => (jar.has(name) ? { name, value: jar.get(name) } : undefined),
    set: (name: string) => writes.push(`set ${name}`),
    delete: (name: string) => writes.push(`delete ${name}`),
  }),
}));

const redirects: string[] = [];
vi.mock("next/navigation", () => ({
  redirect: (url: string) => {
    redirects.push(url);
    throw new Error("NEXT_REDIRECT");
  },
}));

const revalidated: string[] = [];
vi.mock("next/cache", () => ({ revalidatePath: (p: string) => revalidated.push(p) }));

import {
  archiveCompany,
  archiveWarehouse,
  createCompany,
  createWarehouse,
  unarchiveCompany,
  unarchiveWarehouse,
  type ArchiveState,
  type CreateState,
} from "./company-actions";

const ACCESS = "secret-access-token";
const UNAVAILABLE = "Serviço indisponível. Tente novamente em instantes.";
const NAME_ERROR = "Informe um nome de até 120 caracteres.";
const CODE_ERROR = "Use até 20 letras, números ou hífen.";

const fetchMock = vi.fn();
const states: (CreateState | ArchiveState)[] = [];

const form = (entries: Record<string, string>) => {
  const data = new FormData();
  for (const [k, v] of Object.entries(entries)) data.set(k, v);
  return data;
};
const company = (extra: Record<string, string> = {}) =>
  form({ name: "Acme", cnpj: "11222333000181", ...extra });
const warehouse = (extra: Record<string, string> = {}) =>
  form({ companyId: "c1", code: "cd-1", name: "Central", ...extra });

async function run(action: Promise<CreateState>) {
  const state = await action;
  states.push(state);
  return state;
}

const problem = (status: number, detail: string) =>
  new Response(JSON.stringify({ status, detail }), { status });
const created = () => new Response("{}", { status: 201 });

beforeEach(() => {
  jar.clear();
  jar.set("atlas_access", ACCESS);
  writes.length = 0;
  redirects.length = 0;
  revalidated.length = 0;
  vi.stubGlobal("fetch", fetchMock);
  vi.stubEnv("ATLAS_API_URL", "http://api.test");
});

afterEach(() => {
  // SEC-02: nothing returned to the page carries a token.
  for (const text of [...states.map((s) => JSON.stringify(s)), ...redirects]) {
    expect(text).not.toContain(ACCESS);
  }
  states.length = 0;
  fetchMock.mockReset();
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
});

describe("createCompany", () => {
  it("creates the company and revalidates the list", async () => {
    fetchMock.mockResolvedValue(created());
    expect(await run(createCompany({}, company({ name: "  Acme  " })))).toEqual({ ok: true });
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("http://api.test/api/companies");
    expect(init.method).toBe("POST");
    expect(init.headers.get("Authorization")).toBe(`Bearer ${ACCESS}`);
    expect(JSON.parse(init.body)).toEqual({ name: "Acme", cnpj: "11222333000181" });
    expect(revalidated).toEqual(["/empresas"]);
  });

  it("omits a blank CNPJ", async () => {
    fetchMock.mockResolvedValue(created());
    await run(createCompany({}, company({ cnpj: "  " })));
    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({ name: "Acme" });
  });

  it("rejects an invalid name without calling the API", async () => {
    for (const name of ["", "   ", "x".repeat(121)]) {
      const state = await run(createCompany({}, company({ name })));
      expect(state.fieldErrors).toEqual({ name: NAME_ERROR });
    }
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("maps a duplicate name", async () => {
    fetchMock.mockResolvedValue(problem(409, "Company name already in use: Acme"));
    const state = await run(createCompany({}, company()));
    expect(state.fieldErrors).toEqual({ name: "Já existe uma empresa com este nome." });
    expect(state.values).toEqual({ name: "Acme", cnpj: "11222333000181" });
  });

  it("maps CNPJ errors", async () => {
    fetchMock.mockResolvedValueOnce(problem(409, "CNPJ already in use: 11222333000181"));
    expect((await run(createCompany({}, company()))).fieldErrors).toEqual({
      cnpj: "Já existe uma empresa com este CNPJ.",
    });
    fetchMock.mockResolvedValueOnce(problem(400, "Invalid CNPJ: 123"));
    expect((await run(createCompany({}, company()))).fieldErrors).toEqual({
      cnpj: "CNPJ inválido.",
    });
  });

  it("maps a forbidden role", async () => {
    fetchMock.mockResolvedValue(problem(403, "Forbidden"));
    expect((await run(createCompany({}, company()))).formError).toBe(
      "Seu papel não permite esta operação.",
    );
  });

  it("keeps the session when the backend is unavailable", async () => {
    fetchMock.mockRejectedValue(new TypeError("fetch failed"));
    expect((await run(createCompany({}, company()))).formError).toBe(UNAVAILABLE);
    expect(writes).toEqual([]);
    expect(jar.get("atlas_access")).toBe(ACCESS);
  });

  it("sends an expired session through the expired route", async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 401 }));
    await expect(createCompany({}, company())).rejects.toThrow("NEXT_REDIRECT");
    expect(redirects).toEqual(["/auth/expired"]);
    expect(writes).toEqual([]);
  });

  it("maps an archived organization", async () => {
    fetchMock.mockResolvedValue(problem(409, "Organization is archived: o1"));
    expect((await run(createCompany({}, company()))).formError).toBe(
      "Esta organização está arquivada.",
    );
  });

  it("never returns a token", async () => {
    fetchMock.mockResolvedValue(created());
    const ok = await run(createCompany({}, company()));
    fetchMock.mockResolvedValue(problem(409, "Company name already in use: Acme"));
    const failed = await run(createCompany({}, company()));
    for (const s of [ok, failed]) expect(JSON.stringify(s)).not.toContain(ACCESS);
  });
});

describe("createWarehouse", () => {
  it("creates the warehouse and revalidates the company page", async () => {
    fetchMock.mockResolvedValue(created());
    expect(await run(createWarehouse({}, warehouse({ code: " cd-1 " })))).toEqual({ ok: true });
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("http://api.test/api/companies/c1/warehouses");
    expect(init.headers.get("Authorization")).toBe(`Bearer ${ACCESS}`);
    expect(JSON.parse(init.body)).toEqual({ code: "cd-1", name: "Central" });
    expect(revalidated).toEqual(["/empresas/c1"]);
  });

  it("rejects an invalid code without calling the API", async () => {
    for (const code of ["", "   ", "x".repeat(21), "A B", "Á1"]) {
      const state = await run(createWarehouse({}, warehouse({ code })));
      expect(state.fieldErrors).toEqual({ code: CODE_ERROR });
    }
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("rejects an invalid name without calling the API", async () => {
    for (const name of ["", "   ", "x".repeat(121)]) {
      const state = await run(createWarehouse({}, warehouse({ name })));
      expect(state.fieldErrors).toEqual({ name: NAME_ERROR });
    }
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("maps a duplicate code", async () => {
    fetchMock.mockResolvedValue(problem(409, "Warehouse code already in use: CD-1"));
    expect((await run(createWarehouse({}, warehouse()))).fieldErrors).toEqual({
      code: "Já existe um depósito com este código nesta empresa.",
    });
  });

  it("maps a duplicate name", async () => {
    fetchMock.mockResolvedValue(problem(409, "Warehouse name already in use: Central"));
    expect((await run(createWarehouse({}, warehouse()))).fieldErrors).toEqual({
      name: "Já existe um depósito com este nome nesta empresa.",
    });
  });

  it("maps an archived company", async () => {
    fetchMock.mockResolvedValue(problem(409, "Company is archived: c1"));
    expect((await run(createWarehouse({}, warehouse()))).formError).toBe(
      "Esta empresa está arquivada.",
    );
  });
});

const noContent = () => new Response(null, { status: 204 });

describe("archive actions", () => {
  it("archive the company and the warehouse", async () => {
    fetchMock.mockResolvedValue(noContent());
    expect(await archiveCompany("c1")).toEqual({ ok: true });
    expect(await archiveWarehouse("c1", "w1")).toEqual({ ok: true });
    const [[companyUrl, companyInit], [warehouseUrl, warehouseInit]] = fetchMock.mock.calls;
    expect(companyUrl).toBe("http://api.test/api/companies/c1/archive");
    expect(warehouseUrl).toBe("http://api.test/api/companies/c1/warehouses/w1/archive");
    for (const init of [companyInit, warehouseInit]) {
      expect(init.method).toBe("POST");
      expect(init.headers.get("Authorization")).toBe(`Bearer ${ACCESS}`);
    }
    expect(revalidated).toEqual(["/empresas", "/empresas/c1", "/empresas", "/empresas/c1"]);
  });

  it("unarchive the company and the warehouse", async () => {
    fetchMock.mockResolvedValue(noContent());
    expect(await unarchiveCompany("c1")).toEqual({ ok: true });
    expect(await unarchiveWarehouse("c1", "w1")).toEqual({ ok: true });
    expect(fetchMock.mock.calls[0][0]).toBe("http://api.test/api/companies/c1/unarchive");
    expect(fetchMock.mock.calls[1][0]).toBe(
      "http://api.test/api/companies/c1/warehouses/w1/unarchive",
    );
    for (const [, init] of fetchMock.mock.calls) {
      expect(init.headers.get("Authorization")).toBe(`Bearer ${ACCESS}`);
    }
    expect(revalidated).toEqual(["/empresas", "/empresas/c1", "/empresas", "/empresas/c1"]);
  });

  it("map every failure", async () => {
    const calls: (() => Promise<ArchiveState>)[] = [
      () => archiveCompany("c1"),
      () => unarchiveCompany("c1"),
      () => archiveWarehouse("c1", "w1"),
      () => unarchiveWarehouse("c1", "w1"),
    ];
    for (const call of calls) {
      fetchMock.mockResolvedValueOnce(problem(403, "Forbidden"));
      expect(await call()).toEqual({ error: "Seu papel não permite esta operação." });
      fetchMock.mockResolvedValueOnce(problem(409, "Organization is archived: o1"));
      expect(await call()).toEqual({ error: "Esta organização está arquivada." });
      fetchMock.mockRejectedValueOnce(new TypeError("fetch failed"));
      expect(await call()).toEqual({ error: UNAVAILABLE });
      fetchMock.mockResolvedValueOnce(new Response(null, { status: 401 }));
      await expect(call()).rejects.toThrow("NEXT_REDIRECT");
    }
    expect(redirects).toEqual(Array(4).fill("/auth/expired"));
    expect(writes).toEqual([]);
  });
});
