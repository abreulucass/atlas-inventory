"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { Suspense } from "react";

import { SidebarMenuButton, SidebarMenuItem } from "@/components/ui/sidebar";

type Props = { href: string; prefix?: boolean; children: React.ReactNode };

function Item({ href, children, active }: Props & { active: boolean }) {
  return (
    <SidebarMenuItem>
      <SidebarMenuButton isActive={active} render={<Link href={href} />}>
        {children}
      </SidebarMenuButton>
    </SidebarMenuItem>
  );
}

function ActiveItem(props: Props) {
  const pathname = usePathname();
  const active = pathname === props.href || (!!props.prefix && pathname.startsWith(`${props.href}/`));
  return <Item {...props} active={active} />;
}

// The link is in the static shell; only the active state needs the pathname (runtime data under cacheComponents).
export function NavLink(props: Props) {
  return (
    <Suspense fallback={<Item {...props} active={false} />}>
      <ActiveItem {...props} />
    </Suspense>
  );
}
