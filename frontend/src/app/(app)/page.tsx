import { Suspense } from "react";

import { Skeleton } from "@/components/ui/skeleton";
import { getMe } from "@/lib/api";

async function Greeting() {
  const me = await getMe();
  return <h1 className="text-2xl font-semibold">Olá, {me.name}</h1>;
}

export default function HomePage() {
  return (
    <Suspense fallback={<Skeleton className="h-8 w-48" />}>
      <Greeting />
    </Suspense>
  );
}
