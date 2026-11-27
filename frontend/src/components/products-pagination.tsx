import Link from "next/link";

import { buttonVariants } from "@/components/ui/button";
import { productHref, type ProductParams } from "@/lib/catalog-query";
import { cn } from "cn";

function PageLink({ href, children }: { href: string | undefined; children: string }) {
  const className = buttonVariants({ variant: "outline", size: "sm" });
  // A disabled link has no href, so it cannot be followed.
  if (!href) {
    return (
      <a role="link" aria-disabled="true" className={cn(className, "pointer-events-none opacity-50")}>
        {children}
      </a>
    );
  }
  return (
    <Link href={href} className={className}>
      {children}
    </Link>
  );
}

export function ProductsPagination({ params, totalPages }: { params: ProductParams; totalPages: number }) {
  if (totalPages <= 1) return null;
  return (
    <nav aria-label="Paginação" className="flex items-center justify-between gap-4">
      <PageLink href={params.pagina > 1 ? productHref(params, { pagina: params.pagina - 1 }) : undefined}>
        Anterior
      </PageLink>
      <span className="text-sm text-muted-foreground">
        Página {params.pagina} de {totalPages}
      </span>
      <PageLink
        href={params.pagina < totalPages ? productHref(params, { pagina: params.pagina + 1 }) : undefined}
      >
        Próxima
      </PageLink>
    </nav>
  );
}
