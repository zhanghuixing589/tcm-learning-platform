import { describe, it, expect } from "vitest";
import {
  availableModes,
  canExtract,
  canSearchBook,
  initialMode,
} from "./readerModes";
const caps = {
  hasToc: false,
  hasImages: false,
  hasTables: false,
  hasFootnotes: false,
  textSelectable: false,
  searchable: false,
  preciseCitationSupported: false,
  fontSizeAdjustable: false,
};
describe("capability-driven reading", () => {
  it("never offers raw PDF mode without original delivery permission", () => {
    expect(
      availableModes(
        {
          availableReadingModes: ["ORIGINAL_PREVIEW", "PLAIN_TEXT"],
          capabilities: caps,
        },
        false,
        "PDF",
      ),
    ).not.toContain("PDF_ORIGINAL");
  });
  it("keeps book search available when the current page has no reliable text", () => {
    expect(
      canSearchBook([
        { capabilities: caps },
        { capabilities: { ...caps, searchable: true } },
      ]),
    ).toBe(true);
    expect(canExtract({ capabilities: caps }, true)).toBe(false);
  });
  it("requires both selection and precise citation capabilities", () => {
    expect(
      canExtract({ capabilities: { ...caps, textSelectable: true } }, true),
    ).toBe(false);
    expect(
      canExtract(
        {
          capabilities: {
            ...caps,
            textSelectable: true,
            preciseCitationSupported: true,
          },
        },
        false,
      ),
    ).toBe(false);
  });
  it("restores only available saved modes", () => {
    expect(initialMode(["ORIGINAL_PREVIEW"], "STRUCTURED")).toBe(
      "ORIGINAL_PREVIEW",
    );
  });
});
