import { CreateCategoryDialog } from "@/components/create-category-dialog";

export function CategoriesHeader({ canManage }: { canManage: boolean }) {
  return (
    <div className="flex flex-wrap items-center justify-between gap-2">
      <h1 className="text-2xl font-semibold">Categorias</h1>
      {canManage && <CreateCategoryDialog />}
    </div>
  );
}
