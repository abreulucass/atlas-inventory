import { ArchiveControl } from "@/components/archive-control";
import { Badge } from "@/components/ui/badge";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import type { Category } from "@/lib/api";

export function CategoriesTable({ categories, canManage }: { categories: Category[]; canManage: boolean }) {
  if (categories.length === 0) {
    return <p className="text-muted-foreground">Nenhuma categoria cadastrada.</p>;
  }
  return (
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead>Nome</TableHead>
          <TableHead>Status</TableHead>
          {canManage && <TableHead>Ações</TableHead>}
        </TableRow>
      </TableHeader>
      <TableBody>
        {categories.map((category) => {
          const archived = category.status === "ARCHIVED";
          return (
            <TableRow key={category.id}>
              <TableCell className="whitespace-normal font-medium [overflow-wrap:anywhere]">
                {category.name}
              </TableCell>
              <TableCell>{archived ? <Badge variant="secondary">Arquivada</Badge> : "Ativa"}</TableCell>
              {canManage && (
                <TableCell>
                  <ArchiveControl kind="category" id={category.id} archived={archived} />
                </TableCell>
              )}
            </TableRow>
          );
        })}
      </TableBody>
    </Table>
  );
}
