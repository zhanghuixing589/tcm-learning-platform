import { describe, it, expect } from "vitest";
import { safeRedirect, canEnter } from "./access";

describe("route access", () => {
  it("allows only local redirects outside login and register", () => {
    expect(safeRedirect("https://evil.example")).toBe("/account");
    expect(safeRedirect("//evil.example")).toBe("/account");
    expect(safeRedirect("/login")).toBe("/account");
    expect(safeRedirect("/account")).toBe("/account");
  });
  it("requires authentication and the matching management permission", () => {
    expect(canEnter({ requiresAuth: true }, null)).toBe(false);
    expect(
      canEnter({ permission: "system:user:manage" }, { permissions: [] }),
    ).toBe(false);
    expect(
      canEnter(
        { permission: "system:user:manage" },
        { permissions: ["system:user:manage"] },
      ),
    ).toBe(true);
  });
  it("admits an existing reviewer or moderator without requiring administrator permissions", () => {
    const review = {
      requiresAuth: true,
      anyPermissions: ["knowledge:review", "community:moderate"],
    };
    expect(canEnter(review, null)).toBe(false);
    expect(canEnter(review, { permissions: [] })).toBe(false);
    expect(canEnter(review, { permissions: ["knowledge:review"] })).toBe(true);
    expect(canEnter(review, { permissions: ["community:moderate"] })).toBe(
      true,
    );
    expect(canEnter(review, { permissions: ["system:user:manage"] })).toBe(
      false,
    );
    expect(
      canEnter(
        { ...review, permission: "system:job:manage" },
        { permissions: ["knowledge:review"] },
      ),
    ).toBe(false);
    expect(canEnter({ anyPermissions: [] }, { permissions: [] })).toBe(false);
  });
});
