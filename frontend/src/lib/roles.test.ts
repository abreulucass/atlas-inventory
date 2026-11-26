import { describe, expect, it } from "vitest";
import { roleLabel } from "./roles";

describe("roleLabel", () => {
  it("labels the three roles in pt-BR", () => {
    expect(roleLabel("OWNER")).toBe("Proprietário");
    expect(roleLabel("ADMIN")).toBe("Administrador");
    expect(roleLabel("MEMBER")).toBe("Membro");
  });
});
