import type { Unit } from "./api";

/** 14 characters as 00.000.000/0000-00, "—" when missing, anything else as-is. */
export function formatCnpj(cnpj: string | null): string {
  if (!cnpj) return "—";
  return cnpj.length === 14
    ? cnpj.replace(/^(.{2})(.{3})(.{3})(.{4})(.{2})$/, "$1.$2.$3/$4-$5")
    : cnpj;
}

const UNIT_NAMES = {
  UN: "Unidade",
  CX: "Caixa",
  PC: "Peça",
  KG: "Quilograma",
  G: "Grama",
  L: "Litro",
  ML: "Mililitro",
  M: "Metro",
} as const satisfies Record<Unit, string>;

/** The eight unit codes, in the API's order. */
export const UNITS = Object.keys(UNIT_NAMES) as Unit[];

/** "Quilograma (KG)". */
export const formatUnit = (code: Unit): string => `${UNIT_NAMES[code]} (${code})`;
