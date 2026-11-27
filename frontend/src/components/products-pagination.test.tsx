import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import type { ProductParams } from "@/lib/catalog-query";
import { ProductsPagination } from "./products-pagination";

const params = (over: Partial<ProductParams> = {}): ProductParams => ({
  q: undefined,
  categoria: undefined,
  status: "ativos",
  pagina: 1,
  ...over,
});

describe("ProductsPagination", () => {
  it("shows the page count and disables the edge links", () => {
    const { container, rerender } = render(<ProductsPagination params={params()} totalPages={3} />);
    expect(screen.getByText("Página 1 de 3")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Anterior" })).toHaveAttribute("aria-disabled", "true");
    expect(screen.getByRole("link", { name: "Anterior" })).not.toHaveAttribute("href");
    expect(screen.getByRole("link", { name: "Próxima" })).not.toHaveAttribute("aria-disabled");

    rerender(<ProductsPagination params={params({ pagina: 3 })} totalPages={3} />);
    expect(screen.getByText("Página 3 de 3")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Próxima" })).toHaveAttribute("aria-disabled", "true");
    expect(screen.getByRole("link", { name: "Próxima" })).not.toHaveAttribute("href");
    expect(screen.getByRole("link", { name: "Anterior" })).not.toHaveAttribute("aria-disabled");

    rerender(<ProductsPagination params={params()} totalPages={1} />);
    expect(container).toBeEmptyDOMElement();
    rerender(<ProductsPagination params={params()} totalPages={0} />);
    expect(container).toBeEmptyDOMElement();
  });

  it("links to the previous and next pagina", () => {
    render(
      <ProductsPagination params={params({ q: "a b", categoria: "k1", status: "todos", pagina: 2 })} totalPages={3} />,
    );
    expect(screen.getByRole("link", { name: "Anterior" })).toHaveAttribute(
      "href",
      "/produtos?q=a+b&categoria=k1&status=todos",
    );
    expect(screen.getByRole("link", { name: "Próxima" })).toHaveAttribute(
      "href",
      "/produtos?q=a+b&categoria=k1&status=todos&pagina=3",
    );
  });
});
