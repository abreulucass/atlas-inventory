import { CreateProductDialog } from "@/components/create-product-dialog";
import type { Category } from "@/lib/api";

/** `categories`: active ones, offered in the create dialog. */
export function ProductsHeader({ canManage, categories }: { canManage: boolean; categories: Category[] }) {
  return (
    <div className="flex flex-wrap items-center justify-between gap-2">
      <h1 className="text-2xl font-semibold">Produtos</h1>
      {canManage && <CreateProductDialog categories={categories} />}
    </div>
  );
}
