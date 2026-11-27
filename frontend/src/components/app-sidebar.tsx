"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { BuildingIcon, HomeIcon } from "lucide-react";

import {
  Sidebar,
  SidebarContent,
  SidebarGroup,
  SidebarGroupContent,
  SidebarHeader,
  SidebarMenu,
  SidebarMenuButton,
  SidebarMenuItem,
} from "@/components/ui/sidebar";

export function AppSidebar() {
  const pathname = usePathname();
  const onCompanies = pathname === "/empresas" || pathname.startsWith("/empresas/");
  return (
    <Sidebar>
      <SidebarHeader>
        <span className="px-2 py-1 text-base font-semibold">Atlas Inventory</span>
      </SidebarHeader>
      <SidebarContent>
        <SidebarGroup>
          <SidebarGroupContent>
            <SidebarMenu>
              <SidebarMenuItem>
                <SidebarMenuButton isActive={pathname === "/"} render={<Link href="/" />}>
                  <HomeIcon />
                  <span>Início</span>
                </SidebarMenuButton>
              </SidebarMenuItem>
              <SidebarMenuItem>
                <SidebarMenuButton isActive={onCompanies} render={<Link href="/empresas" />}>
                  <BuildingIcon />
                  <span>Empresas</span>
                </SidebarMenuButton>
              </SidebarMenuItem>
            </SidebarMenu>
          </SidebarGroupContent>
        </SidebarGroup>
      </SidebarContent>
    </Sidebar>
  );
}
