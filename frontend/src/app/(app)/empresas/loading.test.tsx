import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import CompaniesLoading from "./loading";

describe("CompaniesLoading", () => {
  it("renders a skeleton", () => {
    render(<CompaniesLoading />);
    expect(screen.getByTestId("companies-skeleton")).toBeInTheDocument();
  });
});
