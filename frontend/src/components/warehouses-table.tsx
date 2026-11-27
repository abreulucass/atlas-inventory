import { ArchiveControl } from "@/components/archive-control";
import { Badge } from "@/components/ui/badge";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import type { Warehouse } from "@/lib/api";

type Props = { warehouses: Warehouse[]; companyId: string; canManage: boolean };

export function WarehousesTable({ warehouses, companyId, canManage }: Props) {
  if (warehouses.length === 0) {
    return <p className="text-muted-foreground">Nenhum depósito cadastrado.</p>;
  }
  return (
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead>Código</TableHead>
          <TableHead>Nome</TableHead>
          <TableHead>Status</TableHead>
          {canManage && <TableHead>Ações</TableHead>}
        </TableRow>
      </TableHeader>
      <TableBody>
        {warehouses.map((warehouse) => {
          const archived = warehouse.status === "ARCHIVED";
          return (
            <TableRow key={warehouse.id}>
              <TableCell className="font-medium">{warehouse.code}</TableCell>
              <TableCell className="whitespace-normal [overflow-wrap:anywhere]">{warehouse.name}</TableCell>
              <TableCell>{archived ? <Badge variant="secondary">Arquivado</Badge> : "Ativo"}</TableCell>
              {canManage && (
                <TableCell>
                  <ArchiveControl kind="warehouse" companyId={companyId} id={warehouse.id} archived={archived} />
                </TableCell>
              )}
            </TableRow>
          );
        })}
      </TableBody>
    </Table>
  );
}
