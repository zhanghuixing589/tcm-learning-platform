import type {
  ReadingMode,
  ReaderCapabilities,
  ReaderWarning,
} from "./readerApi";
export const modeLabels: Record<ReadingMode, string> = {
  STRUCTURED: "结构化正文",
  FIXED_LAYOUT: "固定版式预览",
  PLAIN_TEXT: "规范文本",
  ORIGINAL_PREVIEW: "安全页面预览",
};
export function availableModes(
  unit: {
    availableReadingModes: ReadingMode[];
    capabilities: ReaderCapabilities;
  },
  _download: boolean,
  _format: string,
) {
  return unit.availableReadingModes.filter((mode) =>
    Object.hasOwn(modeLabels, mode),
  );
}
export function initialMode(
  modes: ReadingMode[],
  saved?: ReadingMode,
): ReadingMode {
  return saved && modes.includes(saved) ? saved : modes[0] || "STRUCTURED";
}
export function canExtract(
  unit: { capabilities: ReaderCapabilities },
  authorized: boolean,
) {
  return (
    authorized &&
    unit.capabilities.textSelectable &&
    unit.capabilities.preciseCitationSupported
  );
}
export function canSearchBook(units: { capabilities: ReaderCapabilities }[]) {
  return units.some((unit) => unit.capabilities.searchable);
}
export function warningText(warning: ReaderWarning) {
  return typeof warning === "string" ? warning : warning.message;
}
