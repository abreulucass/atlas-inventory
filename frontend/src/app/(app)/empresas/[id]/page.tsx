import { Suspense } from "react";

import { ArchivedFilter } from "@/components/archived-filter";
import { CompanySummary } from "@/components/company-summary";
import { Skeleton } from "@/components/ui/skeleton";
import { WarehousesTable } from "@/components/warehouses-table";
import { getCompany, getMe, getWarehouses } from "@/lib/api";
import { canManage } from "@/lib/roles";

type Props = {
  params: Promise<{ id: string }>;
  searchParams: Promise<{ arquivados?: string }>;
};

async function Summary({ params }: Pick<Props, "params">) {
  const { id } = await params;
  const [company, me] = await Promise.all([getCompany(id), getMe()]);
  return <CompanySummary company={company} canManage={canManage(me.role)} />;
}

async function Warehouses({ params, searchParams }: Props) {
  const [{ id }, { arquivados }] = await Promise.all([params, searchParams]);
  const archived = arquivados === "1";
  const [warehouses, me] = await Promise.all([getWarehouses(id, archived), getMe()]);
  return (
    <>
      <div>
        <ArchivedFilter
          href={archived ? `/empresas/${id}` : `/empresas/${id}?arquivados=1`}
          checked={archived}
          label="Mostrar arquivados"
        />
      </div>
      <WarehousesTable warehouses={warehouses} companyId={id} canManage={canManage(me.role)} />
    </>
  );
}

export default function CompanyPage(props: Props) {
  return (
    <div className="space-y-4">
      <Suspense fallback={<Skeleton className="h-24 w-64" />}>
        <Summary params={props.params} />
      </Suspense>
      <Suspense fallback={<Skeleton className="h-40 w-full" />}>
        <Warehouses {...props} />
      </Suspense>
    </div>
  );
}
