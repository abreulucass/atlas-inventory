import { act, render, screen, waitFor } from "@testing-library/react";
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
    // only archived items carry the badge
    expect(screen.getByRole("menuitem", { name: "Acme" })).not.toHaveTextContent("Arquivada");
    expect(screen.getAllByText("Arquivada")).toHaveLength(1);
  });

  it("switches to an archived organization", async () => {
    switchOrganization.mockResolvedValue(undefined);
    const user = await open([acme, old]);
    const item = await screen.findByRole("menuitem", { name: /Velha/ });
    expect(item).not.toHaveAttribute("aria-disabled", "true");
    await user.click(item);
    expect(switchOrganization).toHaveBeenCalledExactlyOnceWith("o3");
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
    const user = await open([acme, beta, old]);
    await user.click(await screen.findByRole("menuitem", { name: "Beta" }));
    expect(switchOrganization).toHaveBeenCalledTimes(1);
    // the menu closed after the first selection; reopen and try every item
    await user.click(screen.getByRole("button", { name: /Acme/ }));
    for (const item of await screen.findAllByRole("menuitem")) {
      expect(item).toHaveAttribute("aria-disabled", "true");
      await user.click(item);
    }
    expect(switchOrganization).toHaveBeenCalledTimes(1);
    await act(async () => finish());
  });

  it("closes the menu and returns focus to the trigger after selecting another organization", async () => {
    let finish!: () => void;
    switchOrganization.mockReturnValue(new Promise<void>((resolve) => (finish = resolve)));
    const user = await open([acme, beta]);
    await user.click(await screen.findByRole("menuitem", { name: "Beta" }));
    await waitFor(() => expect(screen.queryByRole("menu")).not.toBeInTheDocument());
    const trigger = screen.getByRole("button", { name: /Acme/ });
    await waitFor(() => expect(trigger).toHaveFocus());
    expect(trigger).toHaveAttribute("aria-busy", "true");
    await act(async () => finish());
    await waitFor(() => expect(trigger).toHaveAttribute("aria-busy", "false"));
  });

  it("does nothing for the active organization", async () => {
    const user = await open([acme, beta]);
    await user.click(await screen.findByRole("menuitem", { name: "Acme" }));
    expect(switchOrganization).not.toHaveBeenCalled();
    await waitFor(() => expect(screen.queryByRole("menu")).not.toBeInTheDocument());
    await waitFor(() => expect(screen.getByRole("button", { name: /Acme/ })).toHaveFocus());
  });

  it("shows the membership error", async () => {
    const message = "Você não faz mais parte desta organização.";
    switchOrganization.mockResolvedValue({ error: message });
    const user = await open([acme, beta]);
    await user.click(await screen.findByRole("menuitem", { name: "Beta" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(message);
    // the error survives the menu closing and sits next to the trigger
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
    expect(screen.getByRole("alert")).not.toHaveClass("truncate");
  });

  it("shows the unavailable error", async () => {
    const message = "Serviço indisponível. Tente novamente em instantes.";
    switchOrganization.mockResolvedValue({ error: message });
    const user = await open([acme, beta]);
    await user.click(await screen.findByRole("menuitem", { name: "Beta" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(message);
  });
});
