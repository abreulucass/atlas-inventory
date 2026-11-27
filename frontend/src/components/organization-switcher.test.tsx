import { act, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

const switchOrganization = vi.fn();
vi.mock("@/lib/auth-actions", () => ({
  switchOrganization: (id: string) => switchOrganization(id),
}));

import type { Organization } from "@/lib/api";

import { OrganizationSwitcher } from "./organization-switcher";

const org = (id: string, name: string, status: Organization["status"] = "ACTIVE"): Organization => ({
  id,
  name,
  slug: id,
  role: "OWNER",
  status,
});
const acme = org("o1", "Acme");
const beta = org("o2", "Beta");
const old = org("o3", "Velha", "ARCHIVED");

const open = async (organizations: Organization[]) => {
  const user = userEvent.setup();
  render(<OrganizationSwitcher organizations={organizations} activeId="o1" />);
  await user.click(screen.getByRole("button", { name: /Acme/ }));
  return user;
};

beforeEach(() => switchOrganization.mockReset());

describe("OrganizationSwitcher", () => {
  it("shows only the name for a single organization", () => {
    render(<OrganizationSwitcher organizations={[acme]} activeId="o1" />);
    expect(screen.getByText("Acme")).toBeInTheDocument();
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });

  it("lists every organization with the active one marked", async () => {
    await open([acme, beta]);
    const items = await screen.findAllByRole("menuitem");
    expect(items.map((i) => i.textContent)).toEqual(["Acme", "Beta"]);
    expect(screen.getByRole("menuitem", { name: "Acme" })).toHaveAttribute("aria-current", "true");
    expect(screen.getByRole("menuitem", { name: "Beta" })).not.toHaveAttribute("aria-current");
  });

  it("shows the archived badge", async () => {
    await open([acme, old]);
    expect(await screen.findByText("Arquivada")).toBeInTheDocument();
    expect(screen.getByRole("menuitem", { name: /Velha/ })).toHaveTextContent("Arquivada");
  });

  it("switches to the selected organization", async () => {
    switchOrganization.mockResolvedValue(undefined);
    const user = await open([acme, beta]);
    await user.click(await screen.findByRole("menuitem", { name: "Beta" }));
    expect(switchOrganization).toHaveBeenCalledExactlyOnceWith("o2");
  });

  it("disables the items while switching", async () => {
    let finish!: () => void;
    switchOrganization.mockReturnValue(new Promise<void>((resolve) => (finish = resolve)));
    const user = await open([acme, beta]);
    await user.click(await screen.findByRole("menuitem", { name: "Beta" }));
    for (const item of screen.getAllByRole("menuitem")) {
      expect(item).toHaveAttribute("aria-disabled", "true");
    }
    await act(async () => finish());
  });

  it("does nothing for the active organization", async () => {
    const user = await open([acme, beta]);
    await user.click(await screen.findByRole("menuitem", { name: "Acme" }));
    expect(switchOrganization).not.toHaveBeenCalled();
  });

  it("shows the membership error", async () => {
    const message = "Você não faz mais parte desta organização.";
    switchOrganization.mockResolvedValue({ error: message });
    const user = await open([acme, beta]);
    await user.click(await screen.findByRole("menuitem", { name: "Beta" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(message);
  });

  it("shows the unavailable error", async () => {
    const message = "Serviço indisponível. Tente novamente em instantes.";
    switchOrganization.mockResolvedValue({ error: message });
    const user = await open([acme, beta]);
    await user.click(await screen.findByRole("menuitem", { name: "Beta" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(message);
  });
});
