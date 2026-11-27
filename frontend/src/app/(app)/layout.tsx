import { Suspense } from "react";

import { AppSidebar } from "@/components/app-sidebar";
import { ThemeToggle } from "@/components/theme-toggle";
import { OrganizationSwitcher } from "@/components/organization-switcher";
import { SidebarInset, SidebarProvider, SidebarTrigger } from "@/components/ui/sidebar";
import { UserMenu } from "@/components/user-menu";
import { getMe, getOrganizations } from "@/lib/api";

async function CurrentUser() {
  const { name, email, role } = await getMe();
  return <UserMenu name={name} email={email} role={role} />;
}

async function CurrentOrganization() {
  const [{ organizationId }, organizations] = await Promise.all([getMe(), getOrganizations()]);
  return <OrganizationSwitcher organizations={organizations} activeId={organizationId} />;
}

// Static shell: cacheComponents forbids request data outside <Suspense>, so user data loads in the slots.
export default function AppLayout({ children }: { children: React.ReactNode }) {
  return (
    <SidebarProvider>
      <AppSidebar />
      <SidebarInset>
        <header className="flex min-h-14 items-center gap-2 border-b px-4 py-2">
          <SidebarTrigger aria-label="Abrir menu" />
          <div className="ml-auto flex min-w-0 items-center gap-1">
            <Suspense>
              <CurrentOrganization />
            </Suspense>
            <ThemeToggle />
            <Suspense>
              <CurrentUser />
            </Suspense>
          </div>
        </header>
        <main className="flex-1 p-4">{children}</main>
      </SidebarInset>
    </SidebarProvider>
  );
}
