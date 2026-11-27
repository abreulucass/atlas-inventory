import { render, screen } from "@testing-library/react";
import { beforeAll, describe, expect, it, vi } from "vitest";

vi.mock("server-only", () => ({}));
vi.mock("@/lib/auth-actions", () => ({ logout: vi.fn(), switchOrganization: vi.fn() }));

let pathname: string | Promise<never> = "/";
vi.mock("next/navigation", () => ({
  usePathname: () => {
    if (typeof pathname !== "string") throw pathname;
    return pathname;
  },
  useRouter: () => ({ push: vi.fn(), refresh: vi.fn() }),
}));
// Never settles: the header slots stay suspended, the shell must not depend on them.
vi.mock("@/lib/api", () => ({
  getMe: () => new Promise(() => {}),
  getOrganizations: () => new Promise(() => {}),
}));

import AppLayout from "./layout";

beforeAll(() => {
  vi.stubGlobal("matchMedia", (query: string) => ({
    matches: false,
    media: query,
    addEventListener: () => {},
    removeEventListener: () => {},
  }));
});

const renderLayout = () => render(<AppLayout>conteúdo</AppLayout>);

describe("AppLayout sidebar", () => {
  it.each([
    ["/", "Início"],
    ["/empresas", "Empresas"],
    ["/empresas/c1", "Empresas"],
  ])("renders the sidebar on %s with %s active", (path, active) => {
    pathname = path;
    renderLayout();
    expect(screen.getByText("conteúdo")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Empresas" })).toHaveAttribute("href", "/empresas");
    expect(screen.getByRole("link", { name: active })).toHaveAttribute("data-active");
    const other = active === "Início" ? "Empresas" : "Início";
    expect(screen.getByRole("link", { name: other })).not.toHaveAttribute("data-active");
  });

  it("keeps the links while the pathname is suspended", () => {
    pathname = new Promise<never>(() => {});
    renderLayout();
    expect(screen.getByRole("link", { name: "Início" })).toHaveAttribute("href", "/");
    expect(screen.getByRole("link", { name: "Empresas" })).toHaveAttribute("href", "/empresas");
    expect(screen.getByRole("link", { name: "Empresas" })).not.toHaveAttribute("data-active");
  });
});
