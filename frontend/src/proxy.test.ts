import { NextRequest } from "next/server";
import { beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("server-only", () => ({}));
vi.mock("@/lib/session", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/session")>()),
  refreshSession: vi.fn(),
}));

import { ServiceUnavailableError } from "@/lib/backend";
import { refreshSession } from "@/lib/session";
import { proxy } from "./proxy";

const refreshMock = vi.mocked(refreshSession);

const request = (path: string, cookies: Record<string, string> = {}) =>
  new NextRequest(new URL(path, "http://app.test"), {
    headers: {
      cookie: Object.entries(cookies)
        .map(([k, v]) => `${k}=${v}`)
        .join("; "),
    },
  });

const pair = {
  accessToken: "new-access",
  refreshToken: "new-refresh",
  tokenType: "Bearer" as const,
  expiresIn: 900,
};

beforeEach(() => {
  refreshMock.mockReset();
});

describe("proxy", () => {
  it("redirects anonymous visitors of a protected route to the login page with next", async () => {
    const res = await proxy(request("/estoque?x=1"));
    expect(res.headers.get("location")).toBe(
      "http://app.test/entrar?next=%2Festoque%3Fx%3D1",
    );
  });

  it("serves the auth pages to anonymous visitors", async () => {
    for (const path of ["/entrar", "/cadastro"]) {
      const res = await proxy(request(path));
      expect(res.headers.get("location")).toBeNull();
      expect(res.headers.get("x-middleware-next")).toBe("1");
    }
  });

  it("redirects signed-in users away from the auth pages", async () => {
    for (const path of ["/entrar", "/cadastro"]) {
      const res = await proxy(request(path, { atlas_access: "a" }));
      expect(res.headers.get("location")).toBe("http://app.test/");
    }
  });

  it("serves protected routes to signed-in users", async () => {
    const res = await proxy(request("/", { atlas_access: "a", atlas_refresh: "r" }));
    expect(res.headers.get("x-middleware-next")).toBe("1");
    expect(refreshMock).not.toHaveBeenCalled();
  });

  it("clears the cookies and redirects when the refresh is rejected", async () => {
    refreshMock.mockResolvedValue("rejected");
    const res = await proxy(request("/estoque", { atlas_refresh: "old" }));
    expect(res.headers.get("location")).toBe("http://app.test/entrar?next=%2Festoque");
    for (const name of ["atlas_access", "atlas_refresh"]) {
      expect(res.cookies.get(name)).toMatchObject({ value: "", path: "/", maxAge: 0 });
    }
  });

  it("clears the cookies and serves the auth page when the refresh is rejected there", async () => {
    refreshMock.mockResolvedValue("rejected");
    const res = await proxy(request("/entrar", { atlas_refresh: "old" }));
    expect(res.headers.get("location")).toBeNull();
    expect(res.cookies.get("atlas_refresh")).toMatchObject({ value: "", maxAge: 0 });
  });

  it("refreshes the session, sets the cookies and forwards the new access token upstream", async () => {
    refreshMock.mockResolvedValue(pair);
    const res = await proxy(request("/", { atlas_refresh: "old", theme: "dark" }));
    expect(refreshMock).toHaveBeenCalledWith("old");
    expect(res.headers.get("location")).toBeNull();
    expect(res.cookies.get("atlas_access")).toMatchObject({
      value: "new-access",
      httpOnly: true,
      maxAge: 870,
    });
    expect(res.cookies.get("atlas_refresh")).toMatchObject({ value: "new-refresh" });
    const forwarded = res.headers.get("x-middleware-request-cookie") ?? "";
    expect(forwarded).toContain("atlas_access=new-access");
    expect(forwarded).toContain("atlas_refresh=new-refresh");
    expect(forwarded).toContain("theme=dark");
  });

  it("refreshes on an auth page and redirects home with the new cookies", async () => {
    refreshMock.mockResolvedValue(pair);
    const res = await proxy(request("/entrar", { atlas_refresh: "old" }));
    expect(res.headers.get("location")).toBe("http://app.test/");
    expect(res.cookies.get("atlas_access")?.value).toBe("new-access");
  });

  it("passes the request through with the cookies kept when the refresh is unavailable", async () => {
    refreshMock.mockImplementation(async () => {
      throw new ServiceUnavailableError();
    });
    const res = await proxy(request("/", { atlas_refresh: "old" }));
    expect(res.headers.get("location")).toBeNull();
    expect(res.headers.get("x-middleware-next")).toBe("1");
    expect(res.cookies.get("atlas_refresh")).toBeUndefined();
  });
});
