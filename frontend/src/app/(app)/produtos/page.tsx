import { Suspense } from "react";

import { ProductFilters } from "@/components/product-filters";
import { ProductsHeader } from "@/components/products-header";
import { ProductsPagination } from "@/components/products-pagination";
import { ProductsTable } from "@/components/products-table";
import { Skeleton } from "@/components/ui/skeleton";
import { getCategories, getMe, getProducts } from "@/lib/api";
import {
  hasProductFilter,
  parseProductParams,
  productApiQuery,
  type ProductParams,
} from "@/lib/catalog-query";
import { canManage } from "@/lib/roles";

type Props = { searchParams: Promise<Record<string, string | string[] | undefined>> };

type Data = {
  params: Promise<ProductParams>;
  me: ReturnType<typeof getMe>;
  categories: ReturnType<typeof getCategories>;
  page: ReturnType<typeof getProducts>;
};

// The header and filters need only the role and the categories: they stay visible while the list loads.
async function Controls({ params, me, categories }: Data) {
  const [{ role }, all] = await Promise.all([me, categories]);
  return (
    <>
      <ProductsHeader canManage={canManage(role)} categories={all} />
      <ProductFilters params={await params} categories={all} />
    </>
  );
}

async function List({ params, me, categories, page }: Data) {
  const [parsed, { role }, all, list] = await Promise.all([params, me, categories, page]);
  return (
    <>
      <ProductsTable
        products={list.items}
        categories={all}
        canManage={canManage(role)}
        filtered={hasProductFilter(parsed)}
      />
      <ProductsPagination params={parsed} totalPages={list.totalPages} />
    </>
  );
}

export default function ProductsPage({ searchParams }: Props) {
  // Each read starts once and is shared by both boundaries.
  const params = searchParams.then(parseProductParams);
  const data: Data = {
    params,
    me: getMe(),
    categories: getCategories(true),
    page: params.then((p) => getProducts(productApiQuery(p))),
  };
  return (
    <div className="space-y-4">
      <Suspense
        fallback={
          <>
            <Skeleton className="h-8 w-40" />
            <Skeleton className="h-8 w-full" />
          </>
        }
      >
        <Controls {...data} />
      </Suspense>
      <Suspense fallback={<Skeleton className="h-40 w-full" />}>
        <List {...data} />
      </Suspense>
    </div>
  );
}
