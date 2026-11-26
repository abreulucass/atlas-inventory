"use client";

import { Button } from "@/components/ui/button";

export default function AppError({ retry }: { retry: () => void }) {
  return (
    <div role="alert" className="grid justify-items-start gap-4">
      <p>Serviço indisponível. Tente novamente em instantes.</p>
      <Button onClick={() => retry()}>Tentar novamente</Button>
    </div>
  );
}
