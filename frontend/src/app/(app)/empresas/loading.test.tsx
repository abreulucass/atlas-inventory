import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import CompaniesLoading from "./loading";

describe("CompaniesLoading", () => {
  it("renders a skeleton", () => {
    render(<CompaniesLoading />);
    const wrapper = screen.getByTestId("companies-skeleton");
    const skeletons = wrapper.querySelectorAll('[data-slot="skeleton"]');
    expect(skeletons).toHaveLength(3);
    expect(skeletons[2]).toHaveClass("h-40", "w-full");
  });
});
