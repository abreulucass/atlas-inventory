import { Skeleton } from "@/components/ui/skeleton";

export default function CompanyLoading() {
  return (
    <div className="space-y-4">
      <Skeleton className="h-5 w-40" />
      <Skeleton className="h-16 w-64" />
      <Skeleton className="h-40 w-full" />
    </div>
  );
}
