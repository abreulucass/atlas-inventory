/** 14 characters as 00.000.000/0000-00, "—" when missing, anything else as-is. */
export function formatCnpj(cnpj: string | null): string {
  if (!cnpj) return "—";
  return cnpj.length === 14
    ? cnpj.replace(/^(.{2})(.{3})(.{3})(.{4})(.{2})$/, "$1.$2.$3/$4-$5")
    : cnpj;
}
