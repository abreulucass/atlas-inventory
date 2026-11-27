import { render, screen, waitFor, within } from "@testing-library/react";
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

const catalog = {
  archiveCategory: vi.fn(),
  unarchiveCategory: vi.fn(),
  archiveProduct: vi.fn(),
  unarchiveProduct: vi.fn(),
};
vi.mock("@/lib/catalog-actions", () => ({
  archiveCategory: (...a: unknown[]) => catalog.archiveCategory(...a),
  unarchiveCategory: (...a: unknown[]) => catalog.unarchiveCategory(...a),
  archiveProduct: (...a: unknown[]) => catalog.archiveProduct(...a),
  unarchiveProduct: (...a: unknown[]) => catalog.unarchiveProduct(...a),
}));

import { ArchiveControl } from "./archive-control";

beforeEach(() => {
  for (const fn of [...Object.values(actions), ...Object.values(catalog)])
    fn.mockReset().mockResolvedValue({ ok: true });
});

const UNAVAILABLE = "Serviço indisponível. Tente novamente em instantes.";

describe("ArchiveControl", () => {
  it("confirms before archiving a company", async () => {
    const user = userEvent.setup();
    render(<ArchiveControl kind="company" companyId="c1" archived={false} />);
    await user.click(screen.getByRole("button", { name: "Arquivar" }));
    const dialog = await screen.findByRole("alertdialog", { name: "Arquivar empresa?" });
    expect(within(dialog).getByRole("button", { name: "Cancelar" })).toBeInTheDocument();
    expect(actions.archiveCompany).not.toHaveBeenCalled();
    await user.click(within(dialog).getByRole("button", { name: "Arquivar" }));
    await waitFor(() => expect(actions.archiveCompany).toHaveBeenCalledWith("c1"));
    await waitFor(() => expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument());
  });

  it("confirms before archiving a warehouse", async () => {
    const user = userEvent.setup();
    render(<ArchiveControl kind="warehouse" companyId="c1" id="w1" archived={false} />);
    await user.click(screen.getByRole("button", { name: "Arquivar" }));
    const dialog = await screen.findByRole("alertdialog", { name: "Arquivar depósito?" });
    expect(within(dialog).getByRole("button", { name: "Cancelar" })).toBeInTheDocument();
    expect(actions.archiveWarehouse).not.toHaveBeenCalled();
    await user.click(within(dialog).getByRole("button", { name: "Arquivar" }));
    await waitFor(() => expect(actions.archiveWarehouse).toHaveBeenCalledWith("c1", "w1"));
  });

  it.each([
    ["company", "Arquivar empresa?", { companyId: "c1" }],
    ["warehouse", "Arquivar depósito?", { companyId: "c1", id: "w1" }],
  ] as const)("cancels without calling the action for a %s", async (kind, title, ids) => {
    const user = userEvent.setup();
    render(<ArchiveControl kind={kind} archived={false} {...ids} />);
    await user.click(screen.getByRole("button", { name: "Arquivar" }));
    const dialog = await screen.findByRole("alertdialog", { name: title });
    await user.click(within(dialog).getByRole("button", { name: "Cancelar" }));
    await waitFor(() => expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument());
    for (const spy of Object.values(actions)) expect(spy).not.toHaveBeenCalled();
  });

  it("cancels without calling the action", async () => {
    const user = userEvent.setup();
    render(<ArchiveControl kind="company" companyId="c1" archived={false} />);
    await user.click(screen.getByRole("button", { name: "Arquivar" }));
    await user.click(await screen.findByRole("button", { name: "Cancelar" }));
    await waitFor(() => expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument());
    for (const spy of Object.values(actions)) expect(spy).not.toHaveBeenCalled();
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
    const user = userEvent.setup();
    const cases = [
      [<ArchiveControl key="a" kind="company" companyId="c1" archived />, actions.unarchiveCompany],
      [<ArchiveControl key="b" kind="category" id="k1" archived />, catalog.unarchiveCategory],
      [<ArchiveControl key="c" kind="product" id="p1" archived />, catalog.unarchiveProduct],
    ] as const;
    for (const [element, spy] of cases) {
      spy.mockResolvedValue({ error: "Seu papel não permite esta operação." });
      const { unmount } = render(element);
      await user.click(screen.getByRole("button", { name: "Desarquivar" }));
      expect(await screen.findByRole("alert")).toHaveTextContent("Seu papel não permite esta operação.");
      unmount();
    }
  });
});

describe("ArchiveControl error placement", () => {
  const cases = [
    ["company archive", "company", false, "archiveCompany"],
    ["company unarchive", "company", true, "unarchiveCompany"],
    ["warehouse archive", "warehouse", false, "archiveWarehouse"],
    ["warehouse unarchive", "warehouse", true, "unarchiveWarehouse"],
  ] as const;

  it.each(cases)("renders the %s error beside its own control only", async (_label, kind, archived, action) => {
    const user = userEvent.setup();
    render(
      <>
        <div data-testid="other">
          <ArchiveControl kind={kind} companyId="c9" id="w9" archived={archived} />
        </div>
        <div data-testid="target">
          <ArchiveControl kind={kind} companyId="c1" id="w1" archived={archived} />
        </div>
      </>,
    );
    for (const message of ["Seu papel não permite esta operação.", UNAVAILABLE]) {
      actions[action].mockResolvedValueOnce({ error: message });
      const target = within(screen.getByTestId("target"));
      await user.click(target.getByRole("button", { name: archived ? "Desarquivar" : "Arquivar" }));
      if (!archived) {
        const dialog = await screen.findByRole("alertdialog");
        await user.click(within(dialog).getByRole("button", { name: "Arquivar" }));
      }
      expect(await target.findByRole("alert")).toHaveTextContent(message);
      expect(within(screen.getByTestId("other")).queryByRole("alert")).not.toBeInTheDocument();
    }
  });
});

describe("ArchiveControl", () => {
  const allSpies = () => [...Object.values(actions), ...Object.values(catalog)];

  it("opens the Arquivar categoria? confirmation", async () => {
    const user = userEvent.setup();
    render(<ArchiveControl kind="category" id="k1" archived={false} />);
    await user.click(screen.getByRole("button", { name: "Arquivar" }));
    const dialog = await screen.findByRole("alertdialog", { name: "Arquivar categoria?" });
    expect(within(dialog).getByRole("button", { name: "Cancelar" })).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: "Arquivar" })).toBeInTheDocument();
    expect(catalog.archiveCategory).not.toHaveBeenCalled();
  });

  it("archives a category on confirm and closes", async () => {
    const user = userEvent.setup();
    render(<ArchiveControl kind="category" id="k1" archived={false} />);
    await user.click(screen.getByRole("button", { name: "Arquivar" }));
    const dialog = await screen.findByRole("alertdialog", { name: "Arquivar categoria?" });
    await user.click(within(dialog).getByRole("button", { name: "Arquivar" }));
    await waitFor(() => expect(catalog.archiveCategory).toHaveBeenCalledWith("k1"));
    await waitFor(() => expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument());
  });

  it("archives a product on confirm and closes", async () => {
    const user = userEvent.setup();
    render(<ArchiveControl kind="product" id="p1" archived={false} />);
    await user.click(screen.getByRole("button", { name: "Arquivar" }));
    const dialog = await screen.findByRole("alertdialog", { name: "Arquivar produto?" });
    await user.click(within(dialog).getByRole("button", { name: "Arquivar" }));
    await waitFor(() => expect(catalog.archiveProduct).toHaveBeenCalledWith("p1"));
    await waitFor(() => expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument());
  });

  it("unarchives a category without confirmation", async () => {
    const user = userEvent.setup();
    render(<ArchiveControl kind="category" id="k1" archived />);
    await user.click(screen.getByRole("button", { name: "Desarquivar" }));
    await waitFor(() => expect(catalog.unarchiveCategory).toHaveBeenCalledWith("k1"));
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
  });

  it("unarchives a product without confirmation", async () => {
    const user = userEvent.setup();
    render(<ArchiveControl kind="product" id="p1" archived />);
    await user.click(screen.getByRole("button", { name: "Desarquivar" }));
    await waitFor(() => expect(catalog.unarchiveProduct).toHaveBeenCalledWith("p1"));
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
  });

  it("cancel closes without calling the action for category and product", async () => {
    const user = userEvent.setup();
    for (const [kind, title] of [
      ["category", "Arquivar categoria?"],
      ["product", "Arquivar produto?"],
    ] as const) {
      const { unmount } = render(<ArchiveControl kind={kind} id="x1" archived={false} />);
      await user.click(screen.getByRole("button", { name: "Arquivar" }));
      const dialog = await screen.findByRole("alertdialog", { name: title });
      await user.click(within(dialog).getByRole("button", { name: "Cancelar" }));
      await waitFor(() => expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument());
      for (const spy of allSpies()) expect(spy).not.toHaveBeenCalled();
      unmount();
    }
  });

  it.each([
    ["category archive", "category", false, "archiveCategory"],
    ["category unarchive", "category", true, "unarchiveCategory"],
    ["product archive", "product", false, "archiveProduct"],
    ["product unarchive", "product", true, "unarchiveProduct"],
  ] as const)("shows the %s error beside its own control only", async (_l, kind, archived, action) => {
    const user = userEvent.setup();
    render(
      <>
        <div data-testid="other">
          <ArchiveControl kind={kind} id="o9" archived={archived} />
        </div>
        <div data-testid="target">
          <ArchiveControl kind={kind} id="t1" archived={archived} />
        </div>
      </>,
    );
    for (const message of ["Seu papel não permite esta operação.", UNAVAILABLE]) {
      catalog[action].mockResolvedValueOnce({ error: message });
      const target = within(screen.getByTestId("target"));
      await user.click(target.getByRole("button", { name: archived ? "Desarquivar" : "Arquivar" }));
      if (!archived) {
        const dialog = await screen.findByRole("alertdialog");
        await user.click(within(dialog).getByRole("button", { name: "Arquivar" }));
      }
      expect(await target.findByRole("alert")).toHaveTextContent(message);
      expect(within(screen.getByTestId("other")).queryByRole("alert")).not.toBeInTheDocument();
    }
  });
});
