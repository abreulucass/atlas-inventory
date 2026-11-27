import { render, screen } from "@testing-library/react";
import { beforeAll, describe, expect, it, vi } from "vitest";

import { SidebarProvider } from "@/components/ui/sidebar";

let pathname = "/";
vi.mock("next/navigation", () => ({ usePathname: () => pathname }));

import { AppSidebar } from "./app-sidebar";

beforeAll(() => {
  vi.stubGlobal("matchMedia", (query: string) => ({
    matches: false,
    media: query,
    addEventListener: () => {},
    removeEventListener: () => {},
  }));
});

const renderSidebar = () =>
  render(
    <SidebarProvider>
      <AppSidebar />
    </SidebarProvider>,
  );

describe("AppSidebar", () => {
  it("shows the app name and the home item", () => {
    render(
      <SidebarProvider>
        <AppSidebar />
      </SidebarProvider>,
    );

    expect(screen.getByText("Atlas Inventory")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Início" })).toHaveAttribute("href", "/");
  });

  it("links to the companies page", () => {
    pathname = "/";
    const { unmount } = renderSidebar();
    expect(screen.getByRole("link", { name: "Empresas" })).toHaveAttribute("href", "/empresas");
    expect(screen.getByRole("link", { name: "Início" })).toHaveAttribute("data-active");
    expect(screen.getByRole("link", { name: "Empresas" })).not.toHaveAttribute("data-active");
    unmount();
    pathname = "/empresas/abc";
    renderSidebar();
    expect(screen.getByRole("link", { name: "Empresas" })).toHaveAttribute("data-active");
    expect(screen.getByRole("link", { name: "Início" })).not.toHaveAttribute("data-active");
  });
});
