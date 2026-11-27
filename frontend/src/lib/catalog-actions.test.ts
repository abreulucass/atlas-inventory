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
  createCategory,
  createProduct,
  updateProduct,
} from "./catalog-actions";
import type { ArchiveState, CreateState } from "./company-actions";

const ACCESS = "secret-access-token";
const REFRESH = "secret-refresh-token";
const UNAVAILABLE = "Serviço indisponível. Tente novamente em instantes.";
const CATEGORY_NAME_ERROR = "Informe um nome de até 80 caracteres.";
const PRODUCT_NAME_ERROR = "Informe um nome de até 200 caracteres.";
const CAT_ID = "0b1f6c1e-6f4e-4c3a-9d2a-1a2b3c4d5e6f";

const fetchMock = vi.fn();
const states: (CreateState | ArchiveState)[] = [];
const track = async <T extends CreateState | ArchiveState>(action: Promise<T>) => {
  const state = await action;
  states.push(state);
  return state;
};

const form = (entries: Record<string, string>) => {
  const data = new FormData();
  for (const [k, v] of Object.entries(entries)) data.set(k, v);
  return data;
};
const category = (extra: Record<string, string> = {}) => form({ name: "Bebidas", ...extra });
const product = (extra: Record<string, string> = {}) =>
  form({ sku: "ab-1", name: "Água", unit: "L", categoryId: CAT_ID, ...extra });
const edit = (extra: Record<string, string> = {}) =>
  form({ id: "p1", version: "3", name: "Água mineral", categoryId: CAT_ID, ...extra });

const problem = (status: number, detail: string) =>
  new Response(JSON.stringify({ status, detail }), { status });
const created = () => new Response("{}", { status: 201 });
const updated = () => new Response("{}", { status: 200 });

beforeEach(() => {
  jar.clear();
  jar.set("atlas_access", ACCESS);
  jar.set("atlas_refresh", REFRESH);
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
    expect(text).not.toContain(REFRESH);
  }
  states.length = 0;
  fetchMock.mockReset();
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
});

describe("createCategory", () => {
  it("posts the trimmed name and revalidates /categorias", async () => {
    fetchMock.mockResolvedValue(created());
    expect(await track(createCategory({}, category({ name: "  Bebidas  " })))).toEqual({ ok: true });
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("http://api.test/api/categories");
    expect(init.method).toBe("POST");
    expect(init.headers.get("Authorization")).toBe(`Bearer ${ACCESS}`);
    expect(JSON.parse(init.body)).toEqual({ name: "Bebidas" });
    expect(revalidated).toEqual(["/categorias"]);
  });

  it("rejects a blank or 81-character name without a request", async () => {
    for (const name of ["", "   ", "x".repeat(81)]) {
      const state = await track(createCategory({}, category({ name })));
      expect(state.fieldErrors).toEqual({ name: CATEGORY_NAME_ERROR });
    }
    expect(fetchMock).not.toHaveBeenCalled();
    fetchMock.mockResolvedValue(created());
    expect(await track(createCategory({}, category({ name: "x".repeat(80) })))).toEqual({ ok: true });
  });

  it("maps a duplicate name to the Nome field", async () => {
    fetchMock.mockResolvedValue(problem(409, "Category name already in use in this organization: Bebidas"));
    const state = await track(createCategory({}, category()));
    expect(state.fieldErrors).toEqual({ name: "Já existe uma categoria com este nome." });
    expect(state.values).toEqual({ name: "Bebidas" });
  });
});

describe("createProduct", () => {
  it("posts sku, name, unit and categoryId and revalidates /produtos", async () => {
    fetchMock.mockResolvedValue(created());
    expect(await track(createProduct({}, product({ sku: " ab-1 ", name: "  Água " })))).toEqual({
      ok: true,
    });
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("http://api.test/api/products");
    expect(init.method).toBe("POST");
    expect(init.headers.get("Authorization")).toBe(`Bearer ${ACCESS}`);
    expect(JSON.parse(init.body)).toEqual({ sku: "ab-1", name: "Água", unit: "L", categoryId: CAT_ID });
    expect(revalidated).toEqual(["/produtos"]);

    await track(createProduct({}, product({ categoryId: "" })));
    expect(JSON.parse(fetchMock.mock.calls[1][1].body).categoryId).toBeNull();
  });

  it("sends no request for invalid input", async () => {
    for (const name of ["", "   ", "x".repeat(201)]) {
      const state = await track(createProduct({}, product({ name })));
      expect(state.fieldErrors).toEqual({ name: PRODUCT_NAME_ERROR });
    }
    const sku = await track(createProduct({}, product({ sku: "a b" })));
    expect(sku.fieldErrors).toEqual({ sku: "Use até 40 letras, números, ponto, hífen ou sublinhado." });
    const unit = await track(createProduct({}, product({ unit: "" })));
    expect(unit.fieldErrors).toEqual({ unit: "Escolha uma unidade." });
    expect(unit.values).toEqual({ sku: "ab-1", name: "Água", unit: "", categoryId: CAT_ID });
    expect(fetchMock).not.toHaveBeenCalled();
    fetchMock.mockResolvedValue(created());
    expect(await track(createProduct({}, product({ name: "x".repeat(200) })))).toEqual({ ok: true });
  });

  it("maps a duplicate SKU to the SKU field", async () => {
    fetchMock.mockResolvedValue(problem(409, "SKU already in use in this organization: AB-1"));
    expect((await track(createProduct({}, product()))).fieldErrors).toEqual({
      sku: "Já existe um produto com este SKU.",
    });
  });

  it("maps an archived category to the Categoria field", async () => {
    fetchMock.mockResolvedValue(problem(409, `Category is archived: ${CAT_ID}`));
    expect((await track(createProduct({}, product()))).fieldErrors).toEqual({
      categoryId: "Esta categoria está arquivada.",
    });
  });

  it("maps a missing category to the Categoria field", async () => {
    fetchMock.mockResolvedValue(problem(404, `Category not found: ${CAT_ID}`));
    expect((await track(createProduct({}, product()))).fieldErrors).toEqual({
      categoryId: "Categoria não encontrada.",
    });
  });
});

describe("updateProduct", () => {
  it("patches version, name and categoryId and revalidates /produtos", async () => {
    fetchMock.mockResolvedValue(updated());
    expect(await track(updateProduct({}, edit({ name: " Água mineral " })))).toEqual({ ok: true });
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("http://api.test/api/products/p1");
    expect(init.method).toBe("PATCH");
    expect(init.headers.get("Authorization")).toBe(`Bearer ${ACCESS}`);
    expect(JSON.parse(init.body)).toEqual({ version: 3, name: "Água mineral", categoryId: CAT_ID });
    expect(revalidated).toEqual(["/produtos"]);

    await track(updateProduct({}, edit({ categoryId: "" })));
    expect(JSON.parse(fetchMock.mock.calls[1][1].body).categoryId).toBeNull();
  });

  it("rejects a blank or 201-character name without a request", async () => {
    for (const name of ["", "  ", "x".repeat(201)]) {
      const state = await track(updateProduct({}, edit({ name })));
      expect(state.fieldErrors).toEqual({ name: PRODUCT_NAME_ERROR });
    }
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("maps a stale version to the form error", async () => {
    fetchMock.mockResolvedValue(problem(409, "Product was changed by someone else, reload it: p1"));
    const state = await track(updateProduct({}, edit()));
    expect(state.formError).toBe("Este produto foi alterado por outra pessoa. Recarregue a página.");
    expect(state.fieldErrors).toBeUndefined();
  });

  it("maps an archived category to the Categoria field", async () => {
    fetchMock.mockResolvedValue(problem(409, `Category is archived: ${CAT_ID}`));
    expect((await track(updateProduct({}, edit()))).fieldErrors).toEqual({
      categoryId: "Esta categoria está arquivada.",
    });
  });
});

type Create = [name: string, call: () => Promise<CreateState>, ok: () => Response];
const creates: Create[] = [
  ["createCategory", () => createCategory({}, category()), created],
  ["createProduct", () => createProduct({}, product()), created],
  ["updateProduct", () => updateProduct({}, edit()), updated],
];

describe("catalog actions", () => {
  it("send the access cookie as Bearer and never return a token for every action", async () => {
    for (const [, call, ok] of creates) {
      fetchMock.mockResolvedValueOnce(ok());
      await track(call());
      fetchMock.mockResolvedValueOnce(problem(409, "SKU already in use in this organization: AB-1"));
      await track(call());
    }
    expect(fetchMock.mock.calls).toHaveLength(6);
    for (const [, init] of fetchMock.mock.calls) {
      expect(init.headers.get("Authorization")).toBe(`Bearer ${ACCESS}`);
    }
    expect(writes).toEqual([]);
  });

  it("map 403 to the role message for every action", async () => {
    for (const [, call] of creates) {
      fetchMock.mockResolvedValueOnce(problem(403, "Forbidden"));
      expect((await track(call())).formError).toBe("Seu papel não permite esta operação.");
    }
    expect(writes).toEqual([]);
    expect(revalidated).toEqual([]);
  });

  it("map an unavailable backend to the service message for every action", async () => {
    for (const [, call] of creates) {
      fetchMock.mockRejectedValueOnce(new TypeError("fetch failed"));
      expect((await track(call())).formError).toBe(UNAVAILABLE);
      fetchMock.mockResolvedValueOnce(problem(503, "Database exploded"));
      const state = await track(call());
      expect(state.formError).toBe(UNAVAILABLE);
      expect(JSON.stringify(state)).not.toContain("exploded");
    }
    expect(writes).toEqual([]);
    expect(jar.get("atlas_access")).toBe(ACCESS);
    expect(jar.get("atlas_refresh")).toBe(REFRESH);
  });

  it("redirect 401 to /auth/expired for every action", async () => {
    for (const [, call] of creates) {
      fetchMock.mockResolvedValueOnce(new Response(null, { status: 401 }));
      await expect(call()).rejects.toThrow("NEXT_REDIRECT");
    }
    expect(redirects).toEqual(Array(3).fill("/auth/expired"));
    expect(writes).toEqual([]);
  });

  it("map an archived organization for every action", async () => {
    for (const [, call] of creates) {
      fetchMock.mockResolvedValueOnce(problem(409, "Organization is archived: o1"));
      expect((await track(call())).formError).toBe("Esta organização está arquivada.");
    }
  });

  it("show the check-data message for an unmapped error", async () => {
    for (const [, call] of creates) {
      fetchMock.mockResolvedValueOnce(new Response("<html>", { status: 400 }));
      const state = await track(call());
      expect(state.formError).toBe("Verifique os dados informados.");
      expect(state.fieldErrors).toBeUndefined();
    }
  });
});
