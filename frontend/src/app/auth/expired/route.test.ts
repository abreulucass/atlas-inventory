import { NextRequest } from "next/server";
import { describe, expect, it, vi } from "vitest";

vi.mock("server-only", () => ({}));

import { GET } from "./route";

const call = (path: string) => {
  const fetchMock = vi.fn();
  vi.stubGlobal("fetch", fetchMock);
  const res = GET(
    new NextRequest(new URL(path, "http://app.test"), {
      headers: { cookie: "atlas_access=a; atlas_refresh=r" },
    }),
  );
  vi.unstubAllGlobals();
  return { res, fetchMock };
};

describe("expired route", () => {
  it("clears the cookies and redirects to the login page", () => {
    const { res, fetchMock } = call("/auth/expired");
    expect(res.headers.get("location")).toBe("http://app.test/entrar");
    for (const name of ["atlas_access", "atlas_refresh"]) {
      expect(res.cookies.get(name)).toMatchObject({ value: "", path: "/", maxAge: 0 });
    }
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("keeps a safe next and drops an unsafe one", () => {
    expect(call("/auth/expired?next=%2Festoque").res.headers.get("location")).toBe(
      "http://app.test/entrar?next=%2Festoque",
    );
    expect(
      call("/auth/expired?next=%2F%2Fevil.test").res.headers.get("location"),
    ).toBe("http://app.test/entrar?next=%2F");
  });
});
