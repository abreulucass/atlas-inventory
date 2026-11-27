// EDG-02 end to end in process: refresh succeeds, the new access token is refused, the user lands
// on /entrar with the cookies cleared, and /entrar does not redirect again.
import { NextRequest } from "next/server";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("server-only", () => ({}));

// The RSC sees the cookies the proxy forwarded upstream.
let rscCookies = new Map<string, string>();
vi.mock("next/headers", () => ({
  cookies: async () => ({
    get: (name: string) => (rscCookies.has(name) ? { name, value: rscCookies.get(name) } : undefined),
  }),
}));
vi.mock("next/navigation", () => ({
  redirect: (url: string) => {
    throw Object.assign(new Error("NEXT_REDIRECT"), { url });
  },
}));

import { getMe } from "@/lib/api";
import { GET as expired } from "./app/auth/expired/route";
import { proxy } from "./proxy";

const fetchMock = vi.fn();
const calls = (path: string) =>
  fetchMock.mock.calls.filter(([url]) => url === `http://api.test${path}`);

beforeEach(() => {
  vi.stubGlobal("fetch", fetchMock);
  vi.stubEnv("ATLAS_API_URL", "http://api.test");
  fetchMock.mockImplementation(async (url: string) => {
    if (url.endsWith("/api/auth/refresh")) {
      return new Response(
        JSON.stringify({
          accessToken: "fresh-access",
          refreshToken: "fresh-refresh",
          tokenType: "Bearer",
          expiresIn: 900,
        }),
        { status: 200 },
      );
    }
    if (url.endsWith("/api/me")) return new Response(null, { status: 401 });
    throw new Error(`unexpected ${url}`);
  });
});

afterEach(() => {
  fetchMock.mockReset();
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
});

const get = (path: string, cookie = "") =>
  new NextRequest(new URL(path, "http://app.test"), { headers: cookie ? { cookie } : {} });

describe("edge", () => {
  it("ends on the login page with cleared cookies when the refreshed token is refused", async () => {
    // 1. Only the refresh cookie: the proxy refreshes and forwards the new access token.
    const proxied = await proxy(get("/", "atlas_refresh=edge-r1"));
    expect(proxied.headers.get("location")).toBeNull();
    expect(calls("/api/auth/refresh")).toHaveLength(1);
    const forwarded = proxied.headers.get("x-middleware-request-cookie") ?? "";
    rscCookies = new Map(
      forwarded.split("; ").map((kv) => kv.split("=") as [string, string]),
    );
    expect(rscCookies.get("atlas_access")).toBe("fresh-access");

    // 2. The RSC calls /api/me with it, gets 401 and goes to /auth/expired.
    const failure = await getMe().catch((e: { url?: string }) => e);
    expect(failure).toMatchObject({ url: "/auth/expired" });
    const [, init] = calls("/api/me")[0];
    expect(init.headers.get("Authorization")).toBe("Bearer fresh-access");

    // 3. /auth/expired clears both cookies and sends the user to /entrar.
    const cleared = expired(get("/auth/expired", "atlas_access=fresh-access; atlas_refresh=fresh-refresh"));
    expect(cleared.headers.get("location")).toBe("http://app.test/entrar");
    for (const name of ["atlas_access", "atlas_refresh"]) {
      expect(cleared.cookies.get(name)).toMatchObject({ value: "", maxAge: 0 });
    }

    // 4. /entrar without cookies is served as is: no loop.
    const login = await proxy(get("/entrar"));
    expect(login.headers.get("location")).toBeNull();
    expect(login.status).toBe(200);
    expect(calls("/api/auth/refresh")).toHaveLength(1);
  });
});
