import { afterEach, it, expect } from "vitest";
import { canonicalSelection } from "./readerSelection";
afterEach(() => {
  document.body.replaceChildren();
  window.getSelection()?.removeAllRanges();
});
it("stores Unicode codepoint ranges and exact cross-block whitespace", () => {
  const root = document.createElement("div");
  root.innerHTML =
    '<p data-block-id="a">甲😀乙 </p><p data-block-id="b">é丙</p>';
  document.body.append(root);
  const ps = root.querySelectorAll("p");
  const range = document.createRange();
  range.setStart(ps[0]!.firstChild!, 1);
  range.setEnd(ps[1]!.firstChild!, 2);
  const selection = window.getSelection()!;
  selection.addRange(range);
  expect(
    canonicalSelection(
      root,
      [
        { id: "a", localId: "a", blockNo: 1, text: "甲😀乙 " },
        { id: "b", localId: "b", blockNo: 2, text: "é丙" },
      ],
      selection,
    ),
  ).toEqual({
    ranges: [
      { blockId: "a", start: 1, end: 4 },
      { blockId: "b", start: 0, end: 2 },
    ],
    quoteText: "😀乙 \né",
  });
});
it("refuses to manufacture locators when displayed text differs from canonical blocks", () => {
  const root = document.createElement("div");
  root.innerHTML = '<p data-block-id="a">错误</p>';
  document.body.append(root);
  const range = document.createRange();
  range.selectNodeContents(root.firstChild!);
  const selection = window.getSelection()!;
  selection.addRange(range);
  expect(() =>
    canonicalSelection(
      root,
      [{ id: "a", localId: "a", blockNo: 1, text: "正确" }],
      selection,
    ),
  ).toThrow("无法精确对齐");
});
