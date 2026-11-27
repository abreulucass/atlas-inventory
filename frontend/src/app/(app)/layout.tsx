import { Suspense } from "react";

import { AppSidebar } from "@/components/app-sidebar";
import { ThemeToggle } from "@/components/theme-toggle";
import { SidebarInset, SidebarProvider, SidebarTrigger } from "@/components/ui/sidebar";
import { UserMenu } from "@/components/user-menu";
import { getMe } from "@/lib/api";

async function CurrentUser() {
  const { name, email, role } = await getMe();
  return <UserMenu name={name} email={email} role={role} />;
}

// Static shell: cacheComponents forbids request data outside <Suspense>, so user data loads in the slots.
export default function AppLayout({ children }: { children: React.ReactNode }) {
  return (
    <SidebarProvider>
      <AppSidebar />
      <SidebarInset>
        <header className="flex h-14 items-center gap-2 border-b px-4">
          <SidebarTrigger aria-label="Abrir menu" />
          <div className="ml-auto flex items-center gap-1">
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
