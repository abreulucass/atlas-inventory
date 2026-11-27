import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import ProductsLoading from "./loading";

describe("ProductsLoading", () => {
  it("renders the table skeleton", () => {
    render(<ProductsLoading />);
    const skeletons = screen.getByTestId("products-skeleton").querySelectorAll('[data-slot="skeleton"]');
    expect(skeletons).toHaveLength(3);
    expect(skeletons[2]).toHaveClass("h-40", "w-full");
  });
});
