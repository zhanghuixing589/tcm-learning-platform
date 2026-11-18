import { describe, expect, it } from "vitest";
import { isValidRegistrationPassword } from "./passwordPolicy";

describe("registration password policy", () => {
  it.each([
    "Abcdefgh12",
    "Abcdefghi!",
    "Admin12345!",
    "Abcdefghij1!",
    "Abcdefg'12",
    'Abcdefg"12',
    "Abcdefg\\12",
    "Abcdefg[12",
  ])("accepts valid ASCII password %s", (password) => {
    expect(isValidRegistrationPassword(password)).toBe(true);
  });
  it.each([
    "",
    "Abcdefg12",
    "Abcdefghijk1!",
    "abcdefgh12",
    "ABCDEFGH12",
    "Abcdefghij",
    "1234567890",
    "!@#$%^&*()",
    "Abcdef12中文",
    "Abcdef12あい",
    "Abcdef12مرح",
    "Abcdefgh１!",
    "Abcdef12😀",
    "Abcdef12 !",
    "Abcdef12\t!",
    "Abcdef12\n!",
    "Abcdef12\u00a0!",
    "Abcdef12\u200b!",
    "Abcdefgh12\n",
    "Abcdefgh12\r",
  ])("rejects invalid password %j", (password) => {
    expect(isValidRegistrationPassword(password)).toBe(false);
  });
});
