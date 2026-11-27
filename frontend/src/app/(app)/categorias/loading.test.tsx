import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import CategoriesLoading from "./loading";

describe("CategoriesLoading", () => {
  it("renders the table skeleton", () => {
    render(<CategoriesLoading />);
    const skeletons = screen.getByTestId("categories-skeleton").querySelectorAll('[data-slot="skeleton"]');
    expect(skeletons).toHaveLength(3);
    expect(skeletons[2]).toHaveClass("h-40", "w-full");
  });
});
