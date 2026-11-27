import type { ExcerptRange, ReaderBlock } from "./readerApi";
/** DOM offsets are UTF-16, stored offsets are Unicode code points. Never trim quotes. */
export function codePointOffset(text: string, utf16Offset: number) {
  return Array.from(text.slice(0, utf16Offset)).length;
}
export function canonicalSelection(
  root: HTMLElement,
  blocks: ReaderBlock[],
  selection: Selection | null,
): { ranges: ExcerptRange[]; quoteText: string } | null {
  if (!selection?.rangeCount || selection.isCollapsed) return null;
  const range = selection.getRangeAt(0);
  if (
    !root.contains(range.startContainer) ||
    !root.contains(range.endContainer)
  )
    return null;
  const ranges: ExcerptRange[] = [];
  const quotes: string[] = [];
  for (const element of root.querySelectorAll<HTMLElement>("[data-block-id]")) {
    if (!range.intersectsNode(element)) continue;
    const block = blocks.find((b) => b.id === element.dataset.blockId);
    if (!block || element.textContent !== block.text)
      throw new Error(
        "排版文字与规范文本无法精确对齐，请在规范文本视图重新选择",
      );
    const all = document.createRange();
    all.selectNodeContents(element);
    const start = all.cloneRange(),
      end = all.cloneRange();
    if (element.contains(range.startContainer))
      start.setEnd(range.startContainer, range.startOffset);
    else start.setEnd(element, 0);
    if (element.contains(range.endContainer))
      end.setEnd(range.endContainer, range.endOffset);
    const from = Array.from(start.toString()).length,
      to = Array.from(end.toString()).length;
    if (to > from) {
      ranges.push({ blockId: block.id, start: from, end: to });
      quotes.push(Array.from(block.text).slice(from, to).join(""));
    }
  }
  return ranges.length ? { ranges, quoteText: quotes.join("\n") } : null;
}
export function evidenceReaderLink(e: {
  documentId: string;
  readingEditionId?: string;
  locator?: Record<string, unknown>;
  segmentId?: string;
}) {
  if (e.readingEditionId) {
    const q = new URLSearchParams({ editionId: e.readingEditionId });
    const ranges = e.locator?.ranges as
      | { unitId?: string; blockId?: string; start?: number; end?: number }[]
      | undefined;
    const first = ranges?.[0];
    if (first?.blockId) q.set("blockId", first.blockId);
    if (first?.start !== undefined) q.set("start", String(first.start));
    if (first?.end !== undefined) q.set("end", String(first.end));
    if (ranges?.length)
      q.set(
        "ranges",
        JSON.stringify(
          ranges.map((r) => ({
            blockId: r.blockId,
            start: r.start,
            end: r.end,
          })),
        ),
      );
    const unit = e.locator?.unitId || first?.unitId;
    if (typeof unit === "string") q.set("unitId", unit);
    return `/sources/${e.documentId}/read?${q}`;
  }
  return `/sources/${e.documentId}${e.segmentId ? `#segment-${e.segmentId}` : ""}`;
}
