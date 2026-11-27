import Link from "next/link";

import { ArchiveControl } from "@/components/archive-control";
import { CreateWarehouseDialog } from "@/components/create-warehouse-dialog";
import { Badge } from "@/components/ui/badge";
import type { Company } from "@/lib/api";
import { formatCnpj } from "@/lib/format";

export function CompanySummary({ company, canManage }: { company: Company; canManage: boolean }) {
  const archived = company.status === "ARCHIVED";
  return (
    <div className="space-y-3">
      <Link href="/empresas" className="text-sm text-muted-foreground underline-offset-4 hover:underline">
        Voltar para empresas
      </Link>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="min-w-0 space-y-1">
          <h1 className="text-2xl font-semibold [overflow-wrap:anywhere]">{company.name}</h1>
          <p className="text-sm text-muted-foreground">CNPJ: {formatCnpj(company.cnpj)}</p>
          <Badge variant={archived ? "secondary" : "default"}>{archived ? "Arquivada" : "Ativa"}</Badge>
        </div>
        {canManage && (
          <div className="flex flex-wrap items-center gap-2">
            {!archived && <CreateWarehouseDialog companyId={company.id} />}
            <ArchiveControl kind="company" companyId={company.id} archived={archived} />
          </div>
        )}
      </div>
    </div>
  );
}
