import Link from "next/link";

import { Badge } from "@/components/ui/badge";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import type { Company } from "@/lib/api";
import { formatCnpj } from "@/lib/format";

export function CompaniesTable({ companies }: { companies: Company[] }) {
  if (companies.length === 0) {
    return <p className="text-muted-foreground">Nenhuma empresa cadastrada.</p>;
  }
  return (
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead>Nome</TableHead>
          <TableHead>CNPJ</TableHead>
          <TableHead>Status</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {companies.map((company) => (
          <TableRow key={company.id}>
            <TableCell className="whitespace-normal [overflow-wrap:anywhere]">
              <Link href={`/empresas/${company.id}`} className="font-medium underline-offset-4 hover:underline">
                {company.name}
              </Link>
            </TableCell>
            <TableCell>{formatCnpj(company.cnpj)}</TableCell>
            <TableCell>
              {company.status === "ACTIVE" ? "Ativa" : <Badge variant="secondary">Arquivada</Badge>}
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}
