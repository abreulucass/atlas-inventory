import { ArchiveControl } from "@/components/archive-control";
import { EditProductDialog } from "@/components/edit-product-dialog";
import { Badge } from "@/components/ui/badge";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import type { Category, Product } from "@/lib/api";
import { formatUnit } from "@/lib/format";

type Props = {
  products: Product[];
  /** Every category, archived included. */
  categories: Category[];
  canManage: boolean;
  /** A search or filter is set: picks the empty message. */
  filtered: boolean;
};

export function ProductsTable({ products, categories, canManage, filtered }: Props) {
  if (products.length === 0) {
    return (
      <p className="text-muted-foreground">
        {filtered ? "Nenhum produto encontrado." : "Nenhum produto cadastrado."}
      </p>
    );
  }
  const byId = new Map(categories.map((category) => [category.id, category]));
  const active = categories.filter((category) => category.status === "ACTIVE");
  return (
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead>SKU</TableHead>
          <TableHead>Nome</TableHead>
          <TableHead>Categoria</TableHead>
          <TableHead>Unidade</TableHead>
          <TableHead>Status</TableHead>
          {canManage && <TableHead>Ações</TableHead>}
        </TableRow>
      </TableHeader>
      <TableBody>
        {products.map((product) => {
          const archived = product.status === "ARCHIVED";
          const category = product.categoryId ? byId.get(product.categoryId) : undefined;
          return (
            <TableRow key={product.id}>
              <TableCell className="font-medium">{product.sku}</TableCell>
              <TableCell className="whitespace-normal [overflow-wrap:anywhere]">{product.name}</TableCell>
              <TableCell className="whitespace-normal [overflow-wrap:anywhere]">{category?.name ?? "—"}</TableCell>
              <TableCell>{formatUnit(product.unit)}</TableCell>
              <TableCell>{archived ? <Badge variant="secondary">Arquivado</Badge> : "Ativo"}</TableCell>
              {canManage && (
                <TableCell>
                  <div className="flex flex-wrap gap-2">
                    {!archived && (
                      <EditProductDialog
                        product={product}
                        categories={active}
                        currentCategory={category?.status === "ARCHIVED" ? category : undefined}
                      />
                    )}
                    <ArchiveControl kind="product" id={product.id} archived={archived} />
                  </div>
                </TableCell>
              )}
            </TableRow>
          );
        })}
      </TableBody>
    </Table>
  );
}
