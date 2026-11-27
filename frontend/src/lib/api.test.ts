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
}));

import { getMe, getOrganizations } from "./api";

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
