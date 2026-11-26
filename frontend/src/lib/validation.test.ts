import { describe, expect, it } from "vitest";
import {
  MESSAGES,
  slugify,
  validateLogin,
  validateRegister,
  type RegisterInput,
} from "./validation";

const valid: RegisterInput = {
  name: "Ana",
  email: "ana@example.com",
  password: "a".repeat(12),
  organizationName: "Acme",
  organizationSlug: "acme",
};

describe("slugify", () => {
  it("derives the slug without accents, symbols or edge hyphens", () => {
    expect(slugify("Ação & Cia. Ltda")).toBe("acao-cia-ltda");
  });

  it("turns each run of characters outside a-z0-9 into one hyphen", () => {
    expect(slugify("  --Foo   Bar__Baz!! ")).toBe("foo-bar-baz");
  });

  it("returns an empty string when nothing is left", () => {
    expect(slugify("!!!")).toBe("");
  });

  it("cuts to 63 characters without a trailing hyphen", () => {
    expect(slugify("a".repeat(70))).toBe("a".repeat(63));
    const cut = slugify(`${"a".repeat(62)} b`);
    expect(cut).toBe("a".repeat(62));
    expect(cut.endsWith("-")).toBe(false);
  });
});

describe("validateRegister", () => {
  it("accepts valid input", () => {
    expect(validateRegister(valid)).toEqual({});
  });

  it("rejects empty and blank names", () => {
    expect(validateRegister({ ...valid, name: "   " }).name).toBe(MESSAGES.name);
    expect(validateRegister({ ...valid, organizationName: "" }).organizationName).toBe(
      MESSAGES.organizationName,
    );
  });

  it("accepts 120 and rejects 121 code points in names, counting an emoji as one", () => {
    expect(validateRegister({ ...valid, name: "😀".repeat(120) }).name).toBeUndefined();
    expect(validateRegister({ ...valid, name: "😀".repeat(121) }).name).toBe(MESSAGES.name);
    expect(
      validateRegister({ ...valid, organizationName: "a".repeat(120) }).organizationName,
    ).toBeUndefined();
    expect(
      validateRegister({ ...valid, organizationName: "a".repeat(121) }).organizationName,
    ).toBe(MESSAGES.organizationName);
  });

  it("counts name length after trimming", () => {
    expect(validateRegister({ ...valid, name: ` ${"a".repeat(120)} ` }).name).toBeUndefined();
  });

  it("rejects passwords under 12 and over 128 code points, untrimmed", () => {
    expect(validateRegister({ ...valid, password: "a".repeat(11) }).password).toBe(
      MESSAGES.pwd,
    );
    expect(validateRegister({ ...valid, password: "a".repeat(12) }).password).toBeUndefined();
    expect(validateRegister({ ...valid, password: "😀".repeat(128) }).password).toBeUndefined();
    expect(validateRegister({ ...valid, password: "a".repeat(129) }).password).toBe(
      MESSAGES.pwd,
    );
    expect(validateRegister({ ...valid, password: ` ${"a".repeat(10)} ` }).password).toBeUndefined();
  });

  it("requires the e-mail shape x@y.z", () => {
    for (const email of ["", "ana", "ana@", "ana@example", "a b@c.d", "a@@c.d"]) {
      expect(validateRegister({ ...valid, email }).email, email).toBe(MESSAGES.email);
    }
  });

  it("accepts 254 and rejects 255 characters in the e-mail", () => {
    const at = (n: number) => `${"a".repeat(n - "@b.co".length)}@b.co`;
    expect(validateRegister({ ...valid, email: at(254) }).email).toBeUndefined();
    expect(validateRegister({ ...valid, email: at(255) }).email).toBe(MESSAGES.email);
  });

  it("accepts slugs of 3 and 63 characters and rejects 2 and 64", () => {
    expect(validateRegister({ ...valid, organizationSlug: "ab" }).organizationSlug).toBe(
      MESSAGES.organizationSlug,
    );
    expect(validateRegister({ ...valid, organizationSlug: "abc" }).organizationSlug).toBeUndefined();
    expect(
      validateRegister({ ...valid, organizationSlug: "a".repeat(63) }).organizationSlug,
    ).toBeUndefined();
    expect(
      validateRegister({ ...valid, organizationSlug: "a".repeat(64) }).organizationSlug,
    ).toBe(MESSAGES.organizationSlug);
  });

  it("rejects slugs outside the pattern", () => {
    for (const organizationSlug of ["Acme", "-acme", "acme-", "ac--me", "ac me", "açme"]) {
      expect(
        validateRegister({ ...valid, organizationSlug }).organizationSlug,
        organizationSlug,
      ).toBe(MESSAGES.organizationSlug);
    }
  });

  it("reports every broken field at once", () => {
    const errors = validateRegister({
      name: "",
      email: "",
      password: "",
      organizationName: "",
      organizationSlug: "",
    });
    expect(Object.keys(errors).sort()).toEqual(
      ["email", "name", "organizationName", "organizationSlug", "password"].sort(),
    );
  });
});

describe("validateLogin", () => {
  it("accepts an e-mail and a password", () => {
    expect(validateLogin({ email: "a@b.c", password: "x" })).toEqual({});
  });

  it("requires both fields", () => {
    expect(validateLogin({ email: "  ", password: "" })).toEqual({
      email: MESSAGES.loginEmail,
      password: MESSAGES.loginPwd,
    });
  });
});
