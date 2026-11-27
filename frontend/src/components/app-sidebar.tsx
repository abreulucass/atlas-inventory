import { BuildingIcon, HomeIcon, PackageIcon, TagsIcon } from "lucide-react";

import { NavLink } from "@/components/nav-link";
import {
  Sidebar,
  SidebarContent,
  SidebarGroup,
  SidebarGroupContent,
  SidebarHeader,
  SidebarMenu,
} from "@/components/ui/sidebar";

export function AppSidebar() {
  return (
    <Sidebar>
      <SidebarHeader>
        <span className="px-2 py-1 text-base font-semibold">Atlas Inventory</span>
      </SidebarHeader>
      <SidebarContent>
        <SidebarGroup>
          <SidebarGroupContent>
            <SidebarMenu>
              <NavLink href="/">
                <HomeIcon />
                <span>Início</span>
              </NavLink>
              <NavLink href="/empresas" prefix>
                <BuildingIcon />
                <span>Empresas</span>
              </NavLink>
              <NavLink href="/produtos" prefix>
                <PackageIcon />
                <span>Produtos</span>
              </NavLink>
              <NavLink href="/categorias" prefix>
                <TagsIcon />
                <span>Categorias</span>
              </NavLink>
            </SidebarMenu>
          </SidebarGroupContent>
        </SidebarGroup>
      </SidebarContent>
    </Sidebar>
  );
}
