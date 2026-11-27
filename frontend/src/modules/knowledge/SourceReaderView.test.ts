import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { mount, flushPromises } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";
import { createMemoryHistory, createRouter } from "vue-router";
import { useSessionStore } from "../../shared/stores/session";
import {
  readerApi,
  type ReaderCapabilities,
  type Reader,
  type ReadingUnit,
} from "./readerApi";
import { stageApi } from "./api";
vi.mock("./ReaderPdf.vue", () => ({
  __esModule: true,
  default: { template: '<div data-test="pdf" />' },
}));
import SourceReaderView from "./SourceReaderView.vue";
const caps: ReaderCapabilities = {
  hasToc: false,
  hasImages: false,
  hasTables: false,
  hasFootnotes: false,
  textSelectable: false,
  searchable: false,
  preciseCitationSupported: false,
  fontSizeAdjustable: false,
};
function unit(id: string, text = false): ReadingUnit {
  return {
    id,
    editionId: "e",
    unitNo: id === "u1" ? 1 : 2,
    unitType: "PAGE",
    title: id === "u1" ? "扫描页" : "文字页",
    pageNumber: 1,
    nodes: [],
    blocks: text ? [{ id: "b", localId: "b", blockNo: 1, text: "甲😀乙" }] : [],
    capabilities: {
      ...caps,
      searchable: text,
      textSelectable: text,
      preciseCitationSupported: text,
    },
    availableReadingModes: text ? ["PLAIN_TEXT"] : ["ORIGINAL_PREVIEW"],
    previewRef: text ? undefined : "preview",
  };
}
async function setup() {
  const pinia = createPinia();
  setActivePinia(pinia);
  const session = useSessionStore();
  session.user = {
    id: "me",
    permissions: ["contribution:submit"],
  } as typeof session.user;
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: "/sources/:id/read", component: SourceReaderView }],
  });
  await router.push("/sources/s/read");
  const wrapper = mount(SourceReaderView, {
    attachTo: document.body,
    global: { plugins: [pinia, router], stubs: { KnowledgeDraftForm: true } },
  });
  await flushPromises();
  return { wrapper, router };
}
beforeEach(() => {
  vi.spyOn(stageApi, "categories").mockResolvedValue([]);
  vi.spyOn(stageApi, "tags").mockResolvedValue([]);
  vi.spyOn(readerApi, "reader").mockResolvedValue({
    document: { id: "s", title: "混合PDF", readingEditionId: "e" },
    edition: {
      id: "e",
      readingVersionNo: 1,
      format: "PDF",
      parseVersion: "p",
      warnings: [],
      overallOutcome: "PARTIAL_SUCCESS",
      stages: {},
    },
    outline: [],
    units: [unit("u1"), unit("u2", true)],
    assets: [
      { id: "a", ref: "preview", mimeType: "image/png", kind: "PAGE_PREVIEW" },
    ],
    capabilities: { read: true, extract: true, download: false },
  } as unknown as Reader);
  vi.spyOn(readerApi, "unit").mockImplementation(async (_s, id) =>
    unit(id, id === "u2"),
  );
  vi.spyOn(readerApi, "blob").mockResolvedValue(new Blob());
  vi.stubGlobal(
    "URL",
    class extends URL {
      static createObjectURL = vi.fn(() => "blob:test");
      static revokeObjectURL = vi.fn();
    },
  );
});
afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  document.body.replaceChildren();
});
describe("reading workspace", () => {
  it("keeps authorized original PDF mode while changing pages and leaves it for explicit text mode", async () => {
    const data = await readerApi.reader("s");
    vi.mocked(readerApi.reader).mockResolvedValue({ ...data, capabilities: { ...data.capabilities, download: true } });
    const { wrapper } = await setup();
    try {
    const original = wrapper.findAll("label").find(label => label.text() === "原始 PDF 页面")!.get("input");
    await original.setValue(true);
    await flushPromises();
    expect(wrapper.find('[data-test="pdf"]').exists()).toBe(true);
    await wrapper.get(".reader-directory select").setValue("u2");
    await flushPromises();
    expect((original.element as HTMLInputElement).checked).toBe(true);
    expect(wrapper.find('[data-test="pdf"]').exists()).toBe(true);
    const mode = wrapper.findAll(".reader-tools select").find(select => select.text().includes("规范文本"))!;
    await mode.setValue("PLAIN_TEXT");
    await flushPromises();
    expect(wrapper.find('[data-test="pdf"]').exists()).toBe(false);
    expect(wrapper.get('[data-block-id="b"]').text()).toBe("甲😀乙");
    } finally { wrapper.unmount(); }
  });
  it("keeps organize panel collapsed, renders preview and never requests PDF bytes when download is denied", async () => {
    const { wrapper } = await setup();
    expect(wrapper.find(".reader-workspace").exists()).toBe(false);
    expect(wrapper.find(".reader-preview img").exists()).toBe(true);
    expect(wrapper.find('[data-test="pdf"]').exists()).toBe(false);
    expect(readerApi.blob).toHaveBeenCalledWith("s", "a");
    expect(readerApi.blob).not.toHaveBeenCalledWith("s");
    expect(wrapper.text()).toContain("当前单元不支持可靠文字引用");
    expect(wrapper.find('input[type="search"]').exists()).toBe(true);
    wrapper.unmount();
  });
  it("searches other reliable units and switches to their exact text view", async () => {
    vi.spyOn(readerApi, "search").mockResolvedValue({
      items: [
        {
          unitId: "u2",
          unitNo: 2,
          blockId: "b",
          localId: "b",
          offset: 1,
          endOffset: 2,
          text: "😀",
        },
      ],
      total: 1,
      page: 1,
      pageSize: 20,
    });
    const { wrapper } = await setup();
    await wrapper.get('input[type="search"]').setValue("😀");
    await wrapper.get(".reader-directory form").trigger("submit");
    await flushPromises();
    await wrapper.get(".reader-hits button").trigger("click");
    await flushPromises();
    expect(readerApi.search).toHaveBeenCalledWith("s", "e", "😀", 1);
    expect(wrapper.get('[data-block-id="b"]').text()).toBe("甲😀乙");
    wrapper.unmount();
  });
  it("retains selection and refuses to claim saved when excerpt persistence fails", async () => {
    vi.spyOn(readerApi, "excerpt").mockRejectedValue(new Error("存储不可用"));
    const { wrapper } = await setup();
    await wrapper.get(".reader-directory select").setValue("u2");
    await flushPromises();
    const node = wrapper.get('[data-block-id="b"]').element.firstChild!;
    const r = document.createRange();
    r.setStart(node, 1);
    r.setEnd(node, 3);
    window.getSelection()!.removeAllRanges();
    window.getSelection()!.addRange(r);
    await wrapper.get(".reader-content").trigger("pointerup");
    await wrapper.get(".reader-selection button:nth-child(2)").trigger("click");
    await flushPromises();
    expect(wrapper.get('[role="alert"]').text()).toContain("摘录未保存");
    expect(wrapper.find(".reader-selection").exists()).toBe(true);
    expect(wrapper.find(".reader-workspace").exists()).toBe(false);
    expect(readerApi.excerpt).toHaveBeenCalledWith(
      "s",
      "e",
      [{ blockId: "b", start: 1, end: 2 }],
      "😀",
    );
    wrapper.unmount();
  });
});
