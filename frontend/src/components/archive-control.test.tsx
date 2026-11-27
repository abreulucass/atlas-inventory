import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

const actions = {
  archiveCompany: vi.fn(),
  unarchiveCompany: vi.fn(),
  archiveWarehouse: vi.fn(),
  unarchiveWarehouse: vi.fn(),
};
vi.mock("@/lib/company-actions", () => ({
  archiveCompany: (...a: unknown[]) => actions.archiveCompany(...a),
  unarchiveCompany: (...a: unknown[]) => actions.unarchiveCompany(...a),
  archiveWarehouse: (...a: unknown[]) => actions.archiveWarehouse(...a),
  unarchiveWarehouse: (...a: unknown[]) => actions.unarchiveWarehouse(...a),
}));

import { ArchiveControl } from "./archive-control";

beforeEach(() => {
  for (const fn of Object.values(actions)) fn.mockReset().mockResolvedValue({ ok: true });
});

describe("ArchiveControl", () => {
  it("confirms before archiving a company", async () => {
    const user = userEvent.setup();
    render(<ArchiveControl kind="company" companyId="c1" archived={false} />);
    await user.click(screen.getByRole("button", { name: "Arquivar" }));
    const dialog = await screen.findByRole("alertdialog");
    expect(dialog).toHaveTextContent("Arquivar empresa?");
    expect(screen.getByRole("button", { name: "Cancelar" })).toBeInTheDocument();
    expect(actions.archiveCompany).not.toHaveBeenCalled();
    await user.click(screen.getAllByRole("button", { name: "Arquivar" }).at(-1)!);
    await waitFor(() => expect(actions.archiveCompany).toHaveBeenCalledWith("c1"));
    await waitFor(() => expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument());
  });

  it("confirms before archiving a warehouse", async () => {
    const user = userEvent.setup();
    render(<ArchiveControl kind="warehouse" companyId="c1" id="w1" archived={false} />);
    await user.click(screen.getByRole("button", { name: "Arquivar" }));
    expect(await screen.findByRole("alertdialog")).toHaveTextContent("Arquivar depósito?");
    expect(screen.getByRole("button", { name: "Cancelar" })).toBeInTheDocument();
    await user.click(screen.getAllByRole("button", { name: "Arquivar" }).at(-1)!);
    await waitFor(() => expect(actions.archiveWarehouse).toHaveBeenCalledWith("c1", "w1"));
  });

  it("cancels without calling the action", async () => {
    const user = userEvent.setup();
    render(<ArchiveControl kind="company" companyId="c1" archived={false} />);
    await user.click(screen.getByRole("button", { name: "Arquivar" }));
    await user.click(await screen.findByRole("button", { name: "Cancelar" }));
    await waitFor(() => expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument());
    expect(actions.archiveCompany).not.toHaveBeenCalled();
  });

  it("unarchives without confirmation", async () => {
    const user = userEvent.setup();
    const { unmount } = render(<ArchiveControl kind="company" companyId="c1" archived />);
    await user.click(screen.getByRole("button", { name: "Desarquivar" }));
    await waitFor(() => expect(actions.unarchiveCompany).toHaveBeenCalledWith("c1"));
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
    unmount();
    render(<ArchiveControl kind="warehouse" companyId="c1" id="w1" archived />);
    await user.click(screen.getByRole("button", { name: "Desarquivar" }));
    await waitFor(() => expect(actions.unarchiveWarehouse).toHaveBeenCalledWith("c1", "w1"));
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
  });

  it("shows the action error", async () => {
    actions.unarchiveCompany.mockResolvedValue({ error: "Seu papel não permite esta operação." });
    const user = userEvent.setup();
    render(<ArchiveControl kind="company" companyId="c1" archived />);
    await user.click(screen.getByRole("button", { name: "Desarquivar" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Seu papel não permite esta operação.");
  });
});
