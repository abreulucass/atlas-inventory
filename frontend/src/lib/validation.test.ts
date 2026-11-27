import { describe, expect, it } from "vitest";
import {
  MESSAGES,
  normalizeEmail,
  slugify,
  strip,
  validateLogin,
  validateRegister,
  type RegisterField,
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

describe("Java parity (REG-04)", () => {
  const strippable = [
    "\t", "\n", "\u000B", "\f", "\r", "\u001C", "\u001F", " ", "\u1680", "\u2000", "\u2006",
    "\u2008", "\u200A", "\u2028", "\u2029", "\u205F", "\u3000",
  ];
  const kept = ["\u00A0", "\u2007", "\u202F", "\uFEFF"];
  const local = (n: number) => "a".repeat(n - "@example.com".length);

  it.each(strippable.map((c) => [c.codePointAt(0)!.toString(16)]))(
    "strips U+%s like String.strip()",
    (hex) => {
      const c = String.fromCodePoint(parseInt(hex, 16));
      expect(strip(`${c}Ana${c}`)).toBe("Ana");
    },
  );

  it.each(kept.map((c) => [c.codePointAt(0)!.toString(16)]))(
    "keeps U+%s like String.strip()",
    (hex) => {
      const c = String.fromCodePoint(parseInt(hex, 16));
      expect(strip(`${c}Ana${c}`)).toBe(`${c}Ana${c}`);
    },
  );

  type Row = [string, Partial<RegisterInput>, RegisterField, string | undefined];
  const rows: Row[] = [
    ["name at 120 code points", { name: "😀".repeat(120) }, "name", undefined],
    ["name at 121 code points", { name: "😀".repeat(121) }, "name", MESSAGES.name],
    ["name at 120 with a strippable edge", { name: `\u2028${"a".repeat(120)}\u3000` }, "name", undefined],
    ["name at 120 with a no-break edge", { name: `\u00A0${"a".repeat(120)}` }, "name", MESSAGES.name],
    ["name blank in Java", { name: "\u2000\u3000" }, "name", MESSAGES.name],
    ["name of a no-break space only", { name: "\u00A0" }, "name", undefined],
    ["organization at 120", { organizationName: "b".repeat(120) }, "organizationName", undefined],
    ["organization at 121", { organizationName: "b".repeat(121) }, "organizationName", MESSAGES.organizationName],
    ["organization with a strippable edge", { organizationName: `\u205F${"b".repeat(120)}` }, "organizationName", undefined],
    ["e-mail at 254 units", { email: `${local(254)}@example.com` }, "email", undefined],
    ["e-mail at 255 units", { email: `${local(255)}@example.com` }, "email", MESSAGES.email],
    ["e-mail at 254 with strippable edges", { email: `\u3000${local(254)}@example.com\t` }, "email", undefined],
    ["e-mail growing to 255 when lowercased", { email: `İ${local(253)}@example.com` }, "email", MESSAGES.email],
    ["e-mail with uppercase at 254", { email: `A${local(253)}@EXAMPLE.COM` }, "email", undefined],
    ["e-mail with a no-break space inside", { email: "ana\u00A0b@example.com" }, "email", undefined],
    ["e-mail with a tab inside", { email: "ana\tb@example.com" }, "email", MESSAGES.email],
    ["password at 12 code points", { password: "😀".repeat(12) }, "password", undefined],
    ["password at 11 code points", { password: "😀".repeat(11) }, "password", MESSAGES.pwd],
    ["password at 128 code points", { password: "😀".repeat(128) }, "password", undefined],
    ["password at 129 code points", { password: "😀".repeat(129) }, "password", MESSAGES.pwd],
    ["slug at 3", { organizationSlug: "abc" }, "organizationSlug", undefined],
    ["slug at 2", { organizationSlug: "ab" }, "organizationSlug", MESSAGES.organizationSlug],
    ["slug at 63", { organizationSlug: "a".repeat(63) }, "organizationSlug", undefined],
    ["slug at 64", { organizationSlug: "a".repeat(64) }, "organizationSlug", MESSAGES.organizationSlug],
  ];

  it.each(rows)("%s", (_, patch, fieldName, message) => {
    const errors = validateRegister({ ...valid, ...patch });
    expect(errors[fieldName]).toBe(message);
    expect(Object.keys(errors).filter((k) => k !== fieldName)).toEqual([]);
  });

  it("normalizes the e-mail like the backend", () => {
    expect(normalizeEmail("\u2003Ana@Example.COM\n")).toBe("ana@example.com");
    expect(normalizeEmail("İ@x.io")).toHaveLength(7);
  });

  it("treats a Java-blank login e-mail as missing", () => {
    expect(validateLogin({ email: "\u3000", password: "x" }).email).toBe(MESSAGES.loginEmail);
  });
});
