import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("server-only", () => ({}));

import { backendFetch, patch, post, ServiceUnavailableError } from "./backend";

const fetchMock = vi.fn();

beforeEach(() => {
  vi.stubGlobal("fetch", fetchMock);
  vi.stubEnv("ATLAS_API_URL", "http://api.test");
});

afterEach(() => {
  fetchMock.mockReset();
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
});

describe("backendFetch", () => {
  it("fails as unavailable when ATLAS_API_URL is not set", async () => {
    vi.stubEnv("ATLAS_API_URL", "");
    const result = backendFetch("/api/me");
    await expect(result).rejects.toBeInstanceOf(ServiceUnavailableError);
    await expect(result).rejects.toThrow(
      "Serviço indisponível. Tente novamente em instantes.",
    );
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("fails as unavailable on a network error", async () => {
    fetchMock.mockRejectedValue(new TypeError("fetch failed"));
    await expect(backendFetch("/api/me")).rejects.toBeInstanceOf(
      ServiceUnavailableError,
    );
  });

  it("fails as unavailable on a 5xx response", async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 503 }));
    await expect(backendFetch("/api/me")).rejects.toBeInstanceOf(
      ServiceUnavailableError,
    );
  });

  it("returns 4xx responses as they are", async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 401 }));
    const res = await backendFetch("/api/me");
    expect(res.status).toBe(401);
  });

  it("calls the configured URL without caching and sets Bearer only with a token", async () => {
    fetchMock.mockResolvedValue(new Response("{}", { status: 200 }));
    await backendFetch("/api/me", { token: "abc" });
    await backendFetch("/api/auth/login", { method: "POST", body: "{}" });
    const [url1, init1] = fetchMock.mock.calls[0];
    const [, init2] = fetchMock.mock.calls[1];
    expect(url1).toBe("http://api.test/api/me");
    expect(init1.cache).toBe("no-store");
    expect(init1.headers.get("Authorization")).toBe("Bearer abc");
    expect(init2.headers.get("Authorization")).toBeNull();
    expect(init2.headers.get("Content-Type")).toBe("application/json");
  });
});

describe("post and patch", () => {
  it("send the method, JSON body and Bearer token", async () => {
    fetchMock.mockImplementation(async () => new Response("{}", { status: 200 }));
    await post("/api/x", { a: 1 }, "abc");
    await patch("/api/x", { b: 2 }, "abc");
    const [[, postInit], [, patchInit]] = fetchMock.mock.calls;
    expect(postInit.method).toBe("POST");
    expect(postInit.body).toBe('{"a":1}');
    expect(patchInit.method).toBe("PATCH");
    expect(patchInit.body).toBe('{"b":2}');
    expect(patchInit.headers.get("Authorization")).toBe("Bearer abc");
  });

  it("return null when the backend is unavailable", async () => {
    fetchMock.mockRejectedValue(new TypeError("fetch failed"));
    expect(await post("/api/x", {})).toBeNull();
    expect(await patch("/api/x", {})).toBeNull();
  });
});
