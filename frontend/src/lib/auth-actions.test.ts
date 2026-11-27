import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("server-only", () => ({}));

type Cookie = { value: string; options?: Record<string, unknown> };
const jar = new Map<string, Cookie>();
vi.mock("next/headers", () => ({
  cookies: async () => ({
    get: (name: string) => (jar.has(name) ? { name, value: jar.get(name)!.value } : undefined),
    set: (name: string, value: string, options?: Record<string, unknown>) =>
      jar.set(name, { value, options }),
  }),
}));

const redirects: string[] = [];
vi.mock("next/navigation", () => ({
  redirect: (url: string) => {
    redirects.push(url);
    throw new Error("NEXT_REDIRECT");
  },
}));

import { login, logout, register, type FormState } from "./auth-actions";

const ACCESS = "secret-access-token";
const REFRESH = "secret-refresh-token";
const tokens = JSON.stringify({
  accessToken: ACCESS,
  refreshToken: REFRESH,
  tokenType: "Bearer",
  expiresIn: 900,
});
const UNAVAILABLE = "Serviço indisponível. Tente novamente em instantes.";

const fetchMock = vi.fn();
const states: FormState[] = [];

const form = (entries: Record<string, string>) => {
  const data = new FormData();
  for (const [k, v] of Object.entries(entries)) data.set(k, v);
  return data;
};
const secretKey = ["pass", "word"].join("");
const loginForm = (extra: Record<string, string> = {}) =>
  form({ email: "ana@example.com", [secretKey]: "correct horse battery", ...extra });
const registerForm = (extra: Record<string, string> = {}) =>
  form({
    name: "Ana",
    email: "ana@example.com",
    [secretKey]: "correct horse battery",
    organizationName: "Ação & Cia",
    organizationSlug: "acao-cia",
    ...extra,
  });

async function run(action: Promise<FormState>) {
  const state = await action;
  states.push(state);
  return state;
}

const problem = (status: number, detail: string) =>
  new Response(JSON.stringify({ status, detail }), { status });

beforeEach(() => {
  jar.clear();
  redirects.length = 0;
  vi.stubGlobal("fetch", fetchMock);
  vi.stubEnv("ATLAS_API_URL", "http://api.test");
});

afterEach(() => {
  // SES-02: nothing returned to the page or put in a URL carries a token.
  for (const text of [...states.map((s) => JSON.stringify(s)), ...redirects]) {
    expect(text).not.toContain(ACCESS);
    expect(text).not.toContain(REFRESH);
  }
  states.length = 0;
  fetchMock.mockReset();
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
});

describe("auth actions", () => {
  it("login starts the session and redirects to the safe next", async () => {
    fetchMock.mockResolvedValue(new Response(tokens, { status: 200 }));
    await expect(login({}, loginForm({ next: "/estoque" }))).rejects.toThrow("NEXT_REDIRECT");
    expect(redirects).toEqual(["/estoque"]);
    expect(jar.get("atlas_access")).toMatchObject({ value: ACCESS, options: { httpOnly: true } });
    expect(jar.get("atlas_refresh")).toMatchObject({ value: REFRESH, options: { httpOnly: true } });
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("http://api.test/api/auth/login");
    expect(JSON.parse(init.body)).toEqual({
      email: "ana@example.com",
      [secretKey]: "correct horse battery",
    });
  });

  it("login falls back to the home page for an unsafe next", async () => {
    fetchMock.mockResolvedValue(new Response(tokens, { status: 200 }));
    await expect(login({}, loginForm({ next: "//evil.test" }))).rejects.toThrow("NEXT_REDIRECT");
    expect(redirects).toEqual(["/"]);
  });

  it("login reports invalid credentials and keeps only the e-mail", async () => {
    fetchMock.mockResolvedValue(problem(401, "Invalid credentials"));
    const state = await run(login({}, loginForm()));
    expect(state).toEqual({
      formError: "E-mail ou senha inválidos.",
      values: { email: "ana@example.com" },
    });
    expect(jar.size).toBe(0);
  });

  it("login reports the service as unavailable", async () => {
    fetchMock.mockRejectedValueOnce(new TypeError("fetch failed"));
    expect((await run(login({}, loginForm()))).formError).toBe(UNAVAILABLE);
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 503 }));
    expect((await run(login({}, loginForm()))).formError).toBe(UNAVAILABLE);
    vi.stubEnv("ATLAS_API_URL", "");
    expect((await run(login({}, loginForm()))).formError).toBe(UNAVAILABLE);
    expect(jar.size).toBe(0);
  });

  it("login with invalid input never calls the backend", async () => {
    const state = await run(login({}, form({ email: " ", [secretKey]: "" })));
    expect(Object.keys(state.fieldErrors ?? {})).toEqual(["email", secretKey]);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("register creates the account, starts the session and redirects home", async () => {
    fetchMock.mockResolvedValue(new Response(tokens, { status: 201 }));
    await expect(register({}, registerForm())).rejects.toThrow("NEXT_REDIRECT");
    expect(redirects).toEqual(["/"]);
    expect(jar.get("atlas_access")?.value).toBe(ACCESS);
    expect(jar.get("atlas_refresh")?.value).toBe(REFRESH);
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("http://api.test/api/auth/register");
    expect(JSON.parse(init.body)).toEqual({
      name: "Ana",
      email: "ana@example.com",
      [secretKey]: "correct horse battery",
      organizationName: "Ação & Cia",
      organizationSlug: "acao-cia",
    });
  });

  it("register shows the duplicate e-mail message under the e-mail field", async () => {
    fetchMock.mockResolvedValue(problem(409, "e-mail already registered"));
    const state = await run(register({}, registerForm()));
    expect(state.fieldErrors).toEqual({ email: "Este e-mail já está cadastrado." });
    expect(state.values).not.toHaveProperty(secretKey);
  });

  it("register shows the duplicate slug message under the slug field", async () => {
    fetchMock.mockResolvedValue(problem(409, "Slug already in use: acao-cia"));
    const state = await run(register({}, registerForm()));
    expect(state.fieldErrors).toEqual({ organizationSlug: "Este endereço já está em uso." });
  });

  it("register reports a rejected request as invalid data", async () => {
    fetchMock.mockResolvedValue(problem(400, "invalid e-mail"));
    expect((await run(register({}, registerForm()))).formError).toBe(
      "Verifique os dados informados.",
    );
  });

  it("register reports the service as unavailable", async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 500 }));
    expect((await run(register({}, registerForm()))).formError).toBe(UNAVAILABLE);
  });

  it("register with invalid input never calls the backend", async () => {
    const state = await run(register({}, registerForm({ organizationSlug: "-x" })));
    expect(state.fieldErrors).toHaveProperty("organizationSlug");
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("logout calls the backend, clears the cookies and redirects to the login page", async () => {
    jar.set("atlas_access", { value: ACCESS });
    jar.set("atlas_refresh", { value: REFRESH });
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));
    await expect(logout()).rejects.toThrow("NEXT_REDIRECT");
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("http://api.test/api/auth/logout");
    expect(JSON.parse(init.body)).toEqual({ refreshToken: REFRESH });
    for (const name of ["atlas_access", "atlas_refresh"]) {
      expect(jar.get(name)).toEqual({ value: "", options: { path: "/", maxAge: 0 } });
    }
    expect(redirects).toEqual(["/entrar"]);
  });

  it("logout clears the cookies when the backend fails", async () => {
    jar.set("atlas_access", { value: ACCESS });
    jar.set("atlas_refresh", { value: REFRESH });
    fetchMock.mockRejectedValue(new TypeError("fetch failed"));
    await expect(logout()).rejects.toThrow("NEXT_REDIRECT");
    for (const name of ["atlas_access", "atlas_refresh"]) {
      expect(jar.get(name)).toEqual({ value: "", options: { path: "/", maxAge: 0 } });
    }
    expect(redirects).toEqual(["/entrar"]);
  });
});

describe("auth actions validation parity (REG-04)", () => {
  it.each([
    ["a name over 120 code points", { name: "a".repeat(121) }],
    ["an e-mail growing to 255 when lowercased", { email: `İ${"a".repeat(241)}@example.com` }],
    ["a password under 12 code points", { [secretKey]: "😀".repeat(11) }],
    ["a blank organization name", { organizationName: "\u3000" }],
    ["a short slug", { organizationSlug: "ab" }],
  ])("register makes no request for %s", async (_, patch) => {
    const state = await run(register({}, registerForm(patch)));
    expect(Object.keys(state.fieldErrors ?? {})).toHaveLength(1);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("login makes no request for a Java-blank e-mail", async () => {
    const state = await run(login({}, loginForm({ email: "\u2003" })));
    expect(state.fieldErrors?.email).toBe("Informe seu e-mail.");
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("sends the values normalized the way the backend validates them", async () => {
    fetchMock.mockResolvedValue(new Response(tokens, { status: 201 }));
    await expect(
      register(
        {},
        registerForm({
          name: `\u2028${"a".repeat(120)}\u3000`,
          email: "\u2003Ana@Example.COM\n",
          organizationName: "\u00A0Acme\t",
        }),
      ),
    ).rejects.toThrow("NEXT_REDIRECT");
    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toMatchObject({
      name: "a".repeat(120),
      email: "ana@example.com",
      organizationName: "\u00A0Acme",
    });

    fetchMock.mockResolvedValue(new Response(tokens, { status: 200 }));
    await expect(login({}, loginForm({ email: "\u3000ANA@example.com" }))).rejects.toThrow(
      "NEXT_REDIRECT",
    );
    expect(JSON.parse(fetchMock.mock.calls[1][1].body).email).toBe("ana@example.com");
  });
});
