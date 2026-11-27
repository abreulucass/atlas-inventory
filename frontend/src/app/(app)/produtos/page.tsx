import { Suspense } from "react";

import { ProductFilters } from "@/components/product-filters";
import { ProductsHeader } from "@/components/products-header";
import { ProductsPagination } from "@/components/products-pagination";
import { ProductsTable } from "@/components/products-table";
import { Skeleton } from "@/components/ui/skeleton";
import { getCategories, getMe, getProducts } from "@/lib/api";
import { hasProductFilter, parseProductParams, productApiQuery } from "@/lib/catalog-query";
import { canManage } from "@/lib/roles";

type Props = { searchParams: Promise<Record<string, string | string[] | undefined>> };

async function Content({ searchParams }: Props) {
  const params = parseProductParams(await searchParams);
  const [{ role }, categories, page] = await Promise.all([
    getMe(),
    getCategories(true),
    getProducts(productApiQuery(params)),
  ]);
  const manage = canManage(role);
  return (
    <>
      <ProductsHeader canManage={manage} categories={categories.filter((c) => c.status === "ACTIVE")} />
      <ProductFilters params={params} categories={categories} />
      <ProductsTable
        products={page.items}
        categories={categories}
        canManage={manage}
        filtered={hasProductFilter(params)}
      />
      <ProductsPagination params={params} totalPages={page.totalPages} />
    </>
  );
}

export default function ProductsPage({ searchParams }: Props) {
  return (
    <div className="space-y-4">
      <Suspense fallback={<Skeleton className="h-40 w-full" />}>
        <Content searchParams={searchParams} />
      </Suspense>
    </div>
  );
}
