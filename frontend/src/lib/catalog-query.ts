export type ProductStatus = "ativos" | "arquivados" | "todos";

export type ProductParams = {
  q: string | undefined;
  categoria: string | undefined;
  status: ProductStatus;
  /** 1-based decimal text, so a huge page survives the round trip to the API exactly. */
  pagina: string;
};

type SearchParams = Record<string, string | string[] | undefined>;

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const API_STATUS = { arquivados: "ARCHIVED", todos: "ALL" } as const;

const ONE = BigInt(1);

/** `pagina` shifted by `by`, as exact decimal text. */
export const shiftPagina = (pagina: string, by: number) => String(BigInt(pagina) + BigInt(by));

const first = (value: string | string[] | undefined) => (Array.isArray(value) ? value[0] : value);

/** Invalid values fall back to their default, so a hand-edited URL never errors. */
export function parseProductParams(searchParams: SearchParams): ProductParams {
  const text = first(searchParams.q) ?? "";
  // Kept as typed, outer spaces included; only a blank text means no search.
  const q = text.trim() === "" ? undefined : text;
  const cat = first(searchParams.categoria);
  const status = first(searchParams.status);
  const pagina = first(searchParams.pagina);
  return {
    q,
    categoria: cat && UUID.test(cat) ? cat : undefined,
    status: status === "arquivados" || status === "todos" ? status : "ativos",
    pagina: pagina && /^\d+$/.test(pagina) && BigInt(pagina) >= ONE ? String(BigInt(pagina)) : "1",
  };
}

/** Query string for `GET /api/products` (no leading "?"). */
export function productApiQuery(params: ProductParams): string {
  const query = new URLSearchParams();
  if (params.q) query.set("q", params.q);
  if (params.categoria) query.set("categoryId", params.categoria);
  if (params.status !== "ativos") query.set("status", API_STATUS[params.status]);
  query.set("page", shiftPagina(params.pagina, -1));
  return query.toString();
}

/** Link to the list keeping the filters; `pagina` defaults to the first page. */
export function productHref(params: ProductParams, { pagina = "1" }: { pagina?: string } = {}): string {
  const query = new URLSearchParams();
  if (params.q) query.set("q", params.q);
  if (params.categoria) query.set("categoria", params.categoria);
  if (params.status !== "ativos") query.set("status", params.status);
  if (BigInt(pagina) > ONE) query.set("pagina", String(pagina));
  const qs = query.toString();
  return qs ? `/produtos?${qs}` : "/produtos";
}

export const hasProductFilter = (params: ProductParams) =>
  Boolean(params.q || params.categoria || params.status !== "ativos");
