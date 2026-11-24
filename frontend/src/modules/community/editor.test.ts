import { expect, it } from "vitest";
import { bodyCharacterCount, idempotencyKeys, newReference, revisionLock } from "./editor";
import { testArticle } from "./testFixtures";

it("counts supplementary Unicode by code point without altering whitespace", () => {
  expect(bodyCharacterCount("  中😀\r\n ")).toBe(6);
});

it("keeps a stable key on retry of the same command and changes it with the payload", () => {
  const keys = idempotencyKeys();
  const first = keys.for("create", { body: "甲\n" }).headers["Idempotency-Key"];
  expect(keys.for("create", { body: "甲\n" }).headers["Idempotency-Key"]).toBe(first);
  expect(keys.for("create", { body: "甲" }).headers["Idempotency-Key"]).not.toBe(first);
});

it("assigns a fresh reference key without compacting existing numbers", () => {
  const first = { ...newReference([]), displayNumber: 4 };
  const second = newReference([first]);
  expect(second.displayNumber).toBe(5);
  expect(second.referenceKey).not.toBe(first.referenceKey);
  expect(first.displayNumber).toBe(4);
});

it("creates locks against the latest version and leaves historical command results alone", () => {
  const article = { ...testArticle("100","旧稿"), revisionId: "50", lockVersion: 6 };
  expect(revisionLock(article)).toEqual({ baseRevisionId: "100", lockVersion: 6 });
});
