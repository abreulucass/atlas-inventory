import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { ArchivedFilter } from "./archived-filter";

describe("ArchivedFilter", () => {
  it("toggles the archived query", () => {
    const { rerender } = render(
      <ArchivedFilter href="/empresas?arquivadas=1" checked={false} label="Mostrar arquivadas" />,
    );
    const off = screen.getByRole("link", { name: "Mostrar arquivadas" });
    expect(off).toHaveAttribute("href", "/empresas?arquivadas=1");
    expect(off).toHaveAttribute("aria-pressed", "false");

    rerender(<ArchivedFilter href="/empresas" checked label="Mostrar arquivadas" />);
    const on = screen.getByRole("link", { name: "Mostrar arquivadas" });
    expect(on).toHaveAttribute("href", "/empresas");
    expect(on).toHaveAttribute("aria-pressed", "true");
  });
});
