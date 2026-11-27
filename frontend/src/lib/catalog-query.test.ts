import { describe, expect, it } from "vitest";

import {
  hasProductFilter,
  parseProductParams,
  productApiQuery,
  productHref,
  type ProductParams,
} from "./catalog-query";

const UUID = "0b1f6c1e-6f4e-4c3a-9d2a-1a2b3c4d5e6f";
const base: ProductParams = { q: undefined, categoria: undefined, status: "ativos", pagina: 1 };
const query = (over: Partial<ProductParams>) => productApiQuery({ ...base, ...over });

describe("parseProductParams", () => {
  it("reads q, categoria, status and pagina", () => {
    expect(
      parseProductParams({ q: " arroz ", categoria: UUID, status: "todos", pagina: "3" }),
    ).toEqual({ q: "arroz", categoria: UUID, status: "todos", pagina: 3 });
  });

  it("falls back to defaults for invalid pagina, status and categoria", () => {
    for (const pagina of ["0", "-1", "abc", "1.5", ""]) {
      expect(parseProductParams({ pagina }).pagina).toBe(1);
    }
    expect(parseProductParams({ status: "x" }).status).toBe("ativos");
    expect(parseProductParams({ categoria: "nope" }).categoria).toBeUndefined();
    expect(parseProductParams({ q: "   " }).q).toBeUndefined();
    expect(parseProductParams({})).toEqual(base);
  });
});

describe("productApiQuery", () => {
  it("maps q to the q parameter", () => {
    expect(query({ q: "arroz" })).toBe("q=arroz");
  });

  it("maps categoria to categoryId", () => {
    expect(query({ categoria: UUID })).toBe(`categoryId=${UUID}`);
  });

  it("omits status for ativos and by default", () => {
    expect(query({ status: "ativos" })).toBe("");
    expect(productApiQuery(parseProductParams({}))).toBe("");
  });

  it("maps arquivados to ARCHIVED and todos to ALL", () => {
    expect(query({ status: "arquivados" })).toBe("status=ARCHIVED");
    expect(query({ status: "todos" })).toBe("status=ALL");
  });

  it("maps pagina n to page n-1", () => {
    expect(query({ pagina: 1 })).toBe("");
    expect(query({ pagina: 2 })).toBe("page=1");
    expect(query({ pagina: 7 })).toBe("page=6");
  });

  it("url-encodes the search text", () => {
    expect(query({ q: "50% & mais" })).toBe("q=50%25+%26+mais");
  });
});

describe("productHref", () => {
  it("keeps q, categoria and status when changing pagina", () => {
    const params = { q: "a b", categoria: UUID, status: "arquivados", pagina: 2 } as const;
    expect(productHref(params, { pagina: 3 })).toBe(
      `/produtos?q=a+b&categoria=${UUID}&status=arquivados&pagina=3`,
    );
    expect(productHref(params, { pagina: 1 })).toBe(
      `/produtos?q=a+b&categoria=${UUID}&status=arquivados`,
    );
  });

  it("omits defaults", () => {
    expect(productHref(base)).toBe("/produtos");
    expect(productHref(base, { pagina: 2 })).toBe("/produtos?pagina=2");
  });
});

describe("hasProductFilter", () => {
  it("is true only when q, categoria or a non-default status is set", () => {
    expect(hasProductFilter(base)).toBe(false);
    expect(hasProductFilter({ ...base, pagina: 4 })).toBe(false);
    expect(hasProductFilter({ ...base, q: "a" })).toBe(true);
    expect(hasProductFilter({ ...base, categoria: UUID })).toBe(true);
    expect(hasProductFilter({ ...base, status: "todos" })).toBe(true);
  });
});
