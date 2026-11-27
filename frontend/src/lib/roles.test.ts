import { describe, expect, it } from "vitest";
import { canManage, roleLabel } from "./roles";

describe("roleLabel", () => {
  it("labels the three roles in pt-BR", () => {
    expect(roleLabel("OWNER")).toBe("Proprietário");
    expect(roleLabel("ADMIN")).toBe("Administrador");
    expect(roleLabel("MEMBER")).toBe("Membro");
  });
});

describe("canManage", () => {
  it.each([
    ["OWNER", true],
    ["ADMIN", true],
    ["MEMBER", false],
  ] as const)("%s -> %s", (role, expected) => {
    expect(canManage(role)).toBe(expected);
  });
});
