import { describe, expect, it } from "vitest";

import {
  hasProductFilter,
  parseProductParams,
  productApiQuery,
  productHref,
  type ProductParams,
} from "./catalog-query";

const UUID = "0b1f6c1e-6f4e-4c3a-9d2a-1a2b3c4d5e6f";
const base: ProductParams = { q: undefined, categoria: undefined, status: "ativos", pagina: "1" };
const query = (over: Partial<ProductParams>) => productApiQuery({ ...base, ...over });

describe("parseProductParams", () => {
  it("reads q, categoria, status and pagina", () => {
    expect(
      parseProductParams({ q: " arroz ", categoria: UUID, status: "todos", pagina: "3" }),
    ).toEqual({ q: " arroz ", categoria: UUID, status: "todos", pagina: "3" });
  });

  it("falls back to defaults for invalid pagina, status and categoria", () => {
    for (const pagina of ["0", "-1", "abc", "1.5", ""]) {
      expect(parseProductParams({ pagina }).pagina).toBe("1");
    }
    expect(parseProductParams({ status: "x" }).status).toBe("ativos");
    expect(parseProductParams({ categoria: "nope" }).categoria).toBeUndefined();
    expect(parseProductParams({ q: "   " }).q).toBeUndefined();
    expect(parseProductParams({})).toEqual(base);
  });
});

describe("productApiQuery", () => {
  it("maps q to the q parameter", () => {
    expect(query({ q: "arroz" })).toBe("q=arroz&page=0");
  });

  it("maps categoria to categoryId", () => {
    expect(query({ categoria: UUID })).toBe(`categoryId=${UUID}&page=0`);
  });

  it("omits status for ativos and by default", () => {
    expect(query({ status: "ativos" })).toBe("page=0");
    expect(productApiQuery(parseProductParams({}))).toBe("page=0");
  });

  it("maps arquivados to ARCHIVED and todos to ALL", () => {
    expect(query({ status: "arquivados" })).toBe("status=ARCHIVED&page=0");
    expect(query({ status: "todos" })).toBe("status=ALL&page=0");
  });

  it("maps pagina n to page n-1", () => {
    expect(query({ pagina: "1" })).toBe("page=0");
    expect(query({ pagina: "2" })).toBe("page=1");
    expect(query({ pagina: "7" })).toBe("page=6");
  });

  it("keeps a huge pagina exact, as plain decimal text", () => {
    const pagina = "10000000000000000000000";
    expect(query(parseProductParams({ pagina }))).toBe("page=9999999999999999999999");
    expect(query({ pagina: "9007199254740993" })).toBe("page=9007199254740992");
    expect(parseProductParams({ pagina: "0002" }).pagina).toBe("2");
  });

  it("url-encodes the search text", () => {
    expect(query({ q: "50% & mais" })).toBe("q=50%25+%26+mais&page=0");
  });

  it("round-trips the search text as typed, outer spaces included", () => {
    const typed = "  Chá + limão #4 & 25%  ";
    const submitted = new URLSearchParams({ q: typed }).toString();
    const parsed = parseProductParams(Object.fromEntries(new URLSearchParams(submitted)));
    expect(parsed.q).toBe(typed);
    expect(new URLSearchParams(productApiQuery(parsed)).get("q")).toBe(typed);
    expect(productApiQuery(parsed)).toBe(`${submitted}&page=0`);
    expect(new URLSearchParams(productHref(parsed).split("?")[1]).get("q")).toBe(typed);
    expect(parseProductParams({ q: " \t " }).q).toBeUndefined();
  });
});

describe("productHref", () => {
  it("keeps q, categoria and status when changing pagina", () => {
    const params = { q: "a b", categoria: UUID, status: "arquivados", pagina: "2" } as const;
    expect(productHref(params, { pagina: "3" })).toBe(
      `/produtos?q=a+b&categoria=${UUID}&status=arquivados&pagina=3`,
    );
    expect(productHref(params, { pagina: "1" })).toBe(
      `/produtos?q=a+b&categoria=${UUID}&status=arquivados`,
    );
  });

  it("omits defaults", () => {
    expect(productHref(base)).toBe("/produtos");
    expect(productHref(base, { pagina: "2" })).toBe("/produtos?pagina=2");
  });
});

describe("hasProductFilter", () => {
  it("is true only when q, categoria or a non-default status is set", () => {
    expect(hasProductFilter(base)).toBe(false);
    expect(hasProductFilter({ ...base, pagina: "4" })).toBe(false);
    expect(hasProductFilter({ ...base, q: "a" })).toBe(true);
    expect(hasProductFilter({ ...base, categoria: UUID })).toBe(true);
    expect(hasProductFilter({ ...base, status: "todos" })).toBe(true);
  });
});
