import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("server-only", () => ({}));

import { ServiceUnavailableError } from "./backend";
import { refreshSession, sessionCookies, type TokenPair } from "./session";

const pair = (n: string): TokenPair => ({
  accessToken: `access-${n}`,
  refreshToken: `refresh-${n}`,
  tokenType: "Bearer",
  expiresIn: 900,
});

const fetchMock = vi.fn();

beforeEach(() => {
  vi.stubGlobal("fetch", fetchMock);
  vi.stubEnv("ATLAS_API_URL", "http://api.test");
});

afterEach(() => {
  fetchMock.mockReset();
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
});

const refreshCalls = () =>
  fetchMock.mock.calls.filter(([url]) => url === "http://api.test/api/auth/refresh");

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status });

describe("sessionCookies", () => {
  it("sets the cookie attributes and lifetimes", () => {
    for (const [env, secure] of [
      ["production", true],
      ["development", false],
    ] as const) {
      vi.stubEnv("NODE_ENV", env);
      expect(sessionCookies(pair("1"))).toEqual([
        {
          name: "atlas_access",
          value: "access-1",
          options: { httpOnly: true, sameSite: "lax", path: "/", secure, maxAge: 870 },
        },
        {
          name: "atlas_refresh",
          value: "refresh-1",
          options: { httpOnly: true, sameSite: "lax", path: "/", secure, maxAge: 2592000 },
        },
      ]);
    }
  });

  it("keeps the access cookie alive at least one second", () => {
    const [access] = sessionCookies({ ...pair("1"), expiresIn: 10 });
    expect(access.options.maxAge).toBe(1);
  });
});

describe("refreshSession", () => {
  it("calls the backend once for concurrent and late callers", async () => {
    vi.useFakeTimers();
    let resolve!: (r: Response) => void;
    fetchMock.mockReturnValueOnce(new Promise<Response>((r) => (resolve = r)));

    const first = refreshSession("old-a");
    const second = refreshSession("old-a");
    const third = refreshSession("old-a");
    await Promise.resolve();
    resolve(json(pair("a")));
    expect(await Promise.all([first, second, third])).toEqual([
      pair("a"),
      pair("a"),
      pair("a"),
    ]);

    vi.advanceTimersByTime(59_000);
    expect(await refreshSession("old-a")).toEqual(pair("a"));
    expect(refreshCalls()).toHaveLength(1);
    expect(JSON.parse(refreshCalls()[0][1].body)).toEqual({ refreshToken: "old-a" });
  });

  it("calls the backend separately for a different token", async () => {
    fetchMock.mockImplementation(async (_url, init) =>
      json(pair(JSON.parse(init.body).refreshToken)),
    );
    const [a, b] = await Promise.all([refreshSession("old-b"), refreshSession("old-c")]);
    expect(a).toEqual(pair("old-b"));
    expect(b).toEqual(pair("old-c"));
    expect(refreshCalls()).toHaveLength(2);
  });

  it("forgets the result 60 seconds after it settled", async () => {
    vi.useFakeTimers();
    fetchMock.mockResolvedValueOnce(json(pair("d1"))).mockResolvedValueOnce(json(pair("d2")));
    expect(await refreshSession("old-d")).toEqual(pair("d1"));
    vi.advanceTimersByTime(60_000);
    expect(await refreshSession("old-d")).toEqual(pair("d2"));
    expect(refreshCalls()).toHaveLength(2);
  });

  it("reports a 401 as rejected", async () => {
    fetchMock.mockResolvedValueOnce(json({ status: 401 }, 401));
    expect(await refreshSession("old-e")).toBe("rejected");
  });

  it("throws as unavailable on a 5xx and retries on the next call", async () => {
    fetchMock
      .mockResolvedValueOnce(new Response(null, { status: 502 }))
      .mockResolvedValueOnce(json(pair("f")));
    await expect(refreshSession("old-f")).rejects.toBeInstanceOf(ServiceUnavailableError);
    expect(await refreshSession("old-f")).toEqual(pair("f"));
  });
});
