import { describe, expect, it } from "vitest";

import { formatCnpj, formatUnit, UNITS } from "./format";

describe("formatCnpj", () => {
  it("masks a 14-character CNPJ", () => {
    expect(formatCnpj("11222333000181")).toBe("11.222.333/0001-81");
  });

  it("shows a dash without CNPJ", () => {
    expect(formatCnpj(null)).toBe("—");
    expect(formatCnpj("")).toBe("—");
  });
});

describe("formatUnit", () => {
  it("names each unit with its code", () => {
    expect(UNITS.map(formatUnit)).toEqual([
      "Unidade (UN)",
      "Caixa (CX)",
      "Peça (PC)",
      "Quilograma (KG)",
      "Grama (G)",
      "Litro (L)",
      "Mililitro (ML)",
      "Metro (M)",
    ]);
  });
});
