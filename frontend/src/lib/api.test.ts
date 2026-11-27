import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("server-only", () => ({}));

const jar = new Map<string, string>();
vi.mock("next/headers", () => ({
  cookies: async () => ({
    get: (name: string) => (jar.has(name) ? { name, value: jar.get(name) } : undefined),
  }),
}));
vi.mock("next/navigation", () => ({
  redirect: (url: string) => {
    throw new Error(`NEXT_REDIRECT ${url}`);
  },
  notFound: () => {
    throw new Error("NEXT_NOT_FOUND");
  },
}));

import { getCompanies, getCompany, getMe, getOrganizations, getWarehouses } from "./api";

const me = {
  id: "u1",
  name: "Ana",
  email: "ana@example.com",
  organizationId: "o1",
  role: "OWNER",
};
const fetchMock = vi.fn();

beforeEach(() => {
  jar.clear();
  vi.stubGlobal("fetch", fetchMock);
  vi.stubEnv("ATLAS_API_URL", "http://api.test");
});

afterEach(() => {
  fetchMock.mockReset();
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
});

describe("getMe", () => {
  it("reads the user with the access cookie as Bearer", async () => {
    jar.set("atlas_access", "tok");
    fetchMock.mockResolvedValue(new Response(JSON.stringify(me), { status: 200 }));
    expect(await getMe()).toEqual(me);
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("http://api.test/api/me");
    expect(init.headers.get("Authorization")).toBe("Bearer tok");
  });

  it("redirects to the expired route on a 401", async () => {
    jar.set("atlas_access", "tok");
    fetchMock.mockResolvedValue(new Response(null, { status: 401 }));
    await expect(getMe()).rejects.toThrow("NEXT_REDIRECT /auth/expired");
  });

  it("redirects to the expired route without an access cookie", async () => {
    await expect(getMe()).rejects.toThrow("NEXT_REDIRECT /auth/expired");
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("fails as unavailable without redirecting when only the refresh cookie is left", async () => {
    jar.set("atlas_refresh", "r1");
    await expect(getMe()).rejects.toMatchObject({ name: "ServiceUnavailableError" });
    expect(fetchMock).not.toHaveBeenCalled();
  });
});

describe("getOrganizations", () => {
  it("reads the organizations with the access cookie as Bearer", async () => {
    const orgs = [{ id: "o1", name: "Acme", slug: "acme", role: "OWNER", status: "ACTIVE" }];
    jar.set("atlas_access", "tok");
    fetchMock.mockResolvedValue(new Response(JSON.stringify(orgs), { status: 200 }));
    expect(await getOrganizations()).toEqual(orgs);
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("http://api.test/api/me/organizations");
    expect(init.headers.get("Authorization")).toBe("Bearer tok");
  });

  it("redirects to the expired route on a 401", async () => {
    jar.set("atlas_access", "tok");
    fetchMock.mockResolvedValue(new Response(null, { status: 401 }));
    await expect(getOrganizations()).rejects.toThrow("NEXT_REDIRECT /auth/expired");
  });

  it("fails on a non-OK answer", async () => {
    jar.set("atlas_access", "tok");
    fetchMock.mockResolvedValue(new Response(null, { status: 404 }));
    await expect(getOrganizations()).rejects.toThrow("failed with 404");
  });
});

const companies = [
  { id: "c1", name: "Alfa", cnpj: null, status: "ACTIVE" },
  { id: "c2", name: "Beta", cnpj: "11222333000181", status: "ARCHIVED" },
];
const warehouses = [
  { id: "w1", companyId: "c1", name: "Central", code: "CD-1", status: "ACTIVE" },
  { id: "w2", companyId: "c1", name: "Norte", code: "CD-2", status: "ARCHIVED" },
];
const ok = (body: unknown) => new Response(JSON.stringify(body), { status: 200 });

describe("getCompanies", () => {
  beforeEach(() => jar.set("atlas_access", "tok"));

  it("returns the companies in the API order", async () => {
    fetchMock.mockResolvedValue(ok(companies));
    expect(await getCompanies(false)).toEqual(companies);
    expect(fetchMock.mock.calls[0][0]).toBe("http://api.test/api/companies");
  });

  it("omits includeArchived by default", async () => {
    fetchMock.mockResolvedValue(ok([]));
    await getCompanies();
    expect(fetchMock.mock.calls[0][0]).toBe("http://api.test/api/companies");
  });

  it("requests archived companies when asked", async () => {
    fetchMock.mockResolvedValue(ok(companies));
    await getCompanies(true);
    expect(fetchMock.mock.calls[0][0]).toBe("http://api.test/api/companies?includeArchived=true");
  });

  it("sends the access token and redirects an expired session", async () => {
    fetchMock.mockResolvedValueOnce(ok([]));
    await getCompanies();
    expect(fetchMock.mock.calls[0][1].headers.get("Authorization")).toBe("Bearer tok");
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 401 }));
    await expect(getCompanies()).rejects.toThrow("NEXT_REDIRECT /auth/expired");
    fetchMock.mockResolvedValueOnce(ok([]));
    await getWarehouses("c1");
    expect(fetchMock.mock.calls[2][1].headers.get("Authorization")).toBe("Bearer tok");
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 401 }));
    await expect(getWarehouses("c1")).rejects.toThrow("NEXT_REDIRECT /auth/expired");
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 401 }));
    await expect(getCompany("c1")).rejects.toThrow("NEXT_REDIRECT /auth/expired");
  });
});

describe("getCompany", () => {
  beforeEach(() => jar.set("atlas_access", "tok"));

  it("returns the company", async () => {
    fetchMock.mockResolvedValue(ok(companies[0]));
    expect(await getCompany("c1")).toEqual(companies[0]);
    expect(fetchMock.mock.calls[0][0]).toBe("http://api.test/api/companies/c1");
  });

  it("renders not found on 404", async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 404 }));
    await expect(getCompany("nope")).rejects.toThrow("NEXT_NOT_FOUND");
  });
});

describe("getWarehouses", () => {
  beforeEach(() => jar.set("atlas_access", "tok"));

  it("returns the warehouses in the API order", async () => {
    fetchMock.mockResolvedValue(ok(warehouses));
    expect(await getWarehouses("c1", false)).toEqual(warehouses);
    expect(fetchMock.mock.calls[0][0]).toBe("http://api.test/api/companies/c1/warehouses");
  });

  it("omits includeArchived by default", async () => {
    fetchMock.mockResolvedValue(ok([]));
    await getWarehouses("c1");
    expect(fetchMock.mock.calls[0][0]).toBe("http://api.test/api/companies/c1/warehouses");
  });

  it("requests archived warehouses when asked", async () => {
    fetchMock.mockResolvedValue(ok(warehouses));
    await getWarehouses("c1", true);
    expect(fetchMock.mock.calls[0][0]).toBe(
      "http://api.test/api/companies/c1/warehouses?includeArchived=true",
    );
  });
});
