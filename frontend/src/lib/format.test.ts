import { describe, expect, it } from "vitest";

import { formatCnpj } from "./format";

describe("formatCnpj", () => {
  it("masks a 14-character CNPJ", () => {
    expect(formatCnpj("11222333000181")).toBe("11.222.333/0001-81");
  });

  it("shows a dash without CNPJ", () => {
    expect(formatCnpj(null)).toBe("—");
    expect(formatCnpj("")).toBe("—");
  });
});
