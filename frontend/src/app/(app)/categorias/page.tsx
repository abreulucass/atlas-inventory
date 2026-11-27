import { Suspense } from "react";

import { ArchivedFilter } from "@/components/archived-filter";
import { CategoriesHeader } from "@/components/categories-header";
import { CategoriesTable } from "@/components/categories-table";
import { Skeleton } from "@/components/ui/skeleton";
import { getCategories, getMe } from "@/lib/api";
import { canManage } from "@/lib/roles";

type Props = { searchParams: Promise<{ arquivadas?: string }> };

async function Header() {
  const { role } = await getMe();
  return <CategoriesHeader canManage={canManage(role)} />;
}

async function List({ searchParams }: Props) {
  const archived = (await searchParams).arquivadas === "1";
  const [categories, { role }] = await Promise.all([getCategories(archived), getMe()]);
  return (
    <>
      <div>
        <ArchivedFilter
          href={archived ? "/categorias" : "/categorias?arquivadas=1"}
          checked={archived}
          label="Mostrar arquivadas"
        />
      </div>
      <CategoriesTable categories={categories} canManage={canManage(role)} />
    </>
  );
}

export default function CategoriesPage({ searchParams }: Props) {
  return (
    <div className="space-y-4">
      <Suspense fallback={<Skeleton className="h-8 w-40" />}>
        <Header />
      </Suspense>
      <Suspense fallback={<Skeleton className="h-40 w-full" />}>
        <List searchParams={searchParams} />
      </Suspense>
    </div>
  );
}
