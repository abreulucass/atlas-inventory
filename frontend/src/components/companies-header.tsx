import { CreateCompanyDialog } from "@/components/create-company-dialog";

export function CompaniesHeader({ canManage }: { canManage: boolean }) {
  return (
    <div className="flex flex-wrap items-center justify-between gap-2">
      <h1 className="text-2xl font-semibold">Empresas</h1>
      {canManage && <CreateCompanyDialog />}
    </div>
  );
}
