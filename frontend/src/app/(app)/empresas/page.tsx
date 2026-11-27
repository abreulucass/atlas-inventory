import { Suspense } from "react";

import { ArchivedFilter } from "@/components/archived-filter";
import { CompaniesHeader } from "@/components/companies-header";
import { CompaniesTable } from "@/components/companies-table";
import { Skeleton } from "@/components/ui/skeleton";
import { getCompanies, getMe } from "@/lib/api";
import { canManage } from "@/lib/roles";

async function Header() {
  const { role } = await getMe();
  return <CompaniesHeader canManage={canManage(role)} />;
}

async function List({ searchParams }: { searchParams: Promise<{ arquivadas?: string }> }) {
  const archived = (await searchParams).arquivadas === "1";
  const companies = await getCompanies(archived);
  return (
    <>
      <div>
        <ArchivedFilter
          href={archived ? "/empresas" : "/empresas?arquivadas=1"}
          checked={archived}
          label="Mostrar arquivadas"
        />
      </div>
      <CompaniesTable companies={companies} />
    </>
  );
}

export default function CompaniesPage({ searchParams }: { searchParams: Promise<{ arquivadas?: string }> }) {
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
