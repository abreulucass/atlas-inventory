import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

const setTheme = vi.fn();
vi.mock("next-themes", () => ({ useTheme: () => ({ setTheme }) }));

import { ThemeToggle } from "./theme-toggle";

describe("ThemeToggle", () => {
  it.each([
    ["Claro", "light"],
    ["Escuro", "dark"],
    ["Sistema", "system"],
  ])("sets the %s theme", async (label, theme) => {
    const user = userEvent.setup();
    render(<ThemeToggle />);

    await user.click(screen.getByRole("button", { name: "Alterar tema" }));
    await user.click(await screen.findByRole("menuitem", { name: label }));

    expect(setTheme).toHaveBeenCalledWith(theme);
  });
});
