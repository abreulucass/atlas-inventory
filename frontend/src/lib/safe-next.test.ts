import { describe, expect, it } from "vitest";
import { safeNext } from "./safe-next";

describe("safeNext", () => {
  it("keeps relative paths with a single leading slash", () => {
    expect(safeNext("/a?b=1")).toBe("/a?b=1");
    expect(safeNext("/")).toBe("/");
  });

  it("falls back to the home page for unsafe targets", () => {
    for (const value of [
      "https://x",
      "//x",
      "/\\x",
      "\\x",
      "javascript:x",
      "/a\nb",
      "/a\tb",
      "/a\u007fb",
      "",
      null,
      undefined,
    ]) {
      expect(safeNext(value), String(value)).toBe("/");
    }
  });
});
