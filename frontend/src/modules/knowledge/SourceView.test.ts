import { afterEach, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import { createPinia } from "pinia";
import { h } from "vue";
import { createMemoryHistory, createRouter, RouterView } from "vue-router";
import { useSessionStore } from "../../shared/stores/session";
import { stageApi, type SourceDocument } from "./api";
import SourceView from "./SourceView.vue";

afterEach(() => vi.restoreAllMocks());
const document = (id: string): SourceDocument => ({
  id,
  title: `资料${id}`,
  sourceType: "BOOK",
  author: null,
  publisher: null,
  publicationYear: null,
  sourceUrl: null,
  bibliographicNote: null,
  fileId: "9",
  fileName: "原始.txt",
  reviewState: "DRAFT",
  availability: "ACTIVE",
  submittedBy: "1",
  parentDocumentId: null,
  createdAt: "",
  parseRun: null,
});
async function setup() {
  const pinia = createPinia();
  useSessionStore(pinia).user = {
    id: "1",
    username: "author",
    nickname: "作者",
    bio: null,
    status: "ACTIVE",
    roles: ["CONTRIBUTOR"],
    permissions: ["contribution:submit"],
    createdAt: "",
  };
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/contribute/sources/:id", component: SourceView },
      { path: "/contribute", component: { template: "<div />" } },
    ],
  });
  await router.push("/contribute/sources/11");
  const wrapper = mount(
    { render: () => h(RouterView) },
    { global: { plugins: [pinia, router] } },
  );
  await flushPromises();
  return { router, wrapper };
}
it.each(["new.txt", "中医.EPUB"])("never patches another source after navigation while %s is uploading", async (fileName) => {
  vi.spyOn(stageApi, "source").mockImplementation(async (id) => document(id));
  let resolveUpload!: (value: any) => void;
  vi.spyOn(stageApi, "upload").mockImplementation(
    () =>
      new Promise((resolve) => {
        resolveUpload = resolve;
      }),
  );
  const save = vi
    .spyOn(stageApi, "saveSource")
    .mockResolvedValue(document("11"));
  const { router, wrapper } = await setup();
  const input = wrapper.get("input[type=file]");
  Object.defineProperty(input.element, "files", {
    value: [new File(["test"], fileName)],
  });
  await input.trigger("change");
  await wrapper.get("form").trigger("submit");
  await flushPromises();
  expect(stageApi.upload).toHaveBeenCalled();
  await router.push("/contribute/sources/22");
  await flushPromises();
  resolveUpload({ id: "99", originalName: "new.txt" });
  await flushPromises();
  expect(save).not.toHaveBeenCalled();
  expect((wrapper.get("input").element as HTMLInputElement).value).toBe(
    "资料22",
  );
  wrapper.unmount();
});
it("keeps the new source form empty when a previous source detail resolves late", async () => {
  let resolveSource!: (value: SourceDocument) => void;
  vi.spyOn(stageApi, "source").mockImplementation(
    () =>
      new Promise((resolve) => {
        resolveSource = resolve;
      }),
  );
  const { router, wrapper } = await setup();
  await router.push("/contribute/sources/new");
  await flushPromises();
  resolveSource(document("11"));
  await flushPromises();
  expect(wrapper.get("h1").text()).toBe("添加来源资料");
  expect((wrapper.get("input").element as HTMLInputElement).value).toBe("");
  expect(wrapper.text()).not.toContain("资料11");
  wrapper.unmount();
});
it("requires saving metadata before parsing or submitting the displayed source", async () => {
  vi.spyOn(stageApi, "source").mockResolvedValue({
    ...document("11"),
    parseRun: {
      id: "12",
      status: "SUCCEEDED",
      errorMessage: null,
      parseVersion: "1",
    },
  });
  vi.spyOn(stageApi, "segments").mockResolvedValue({
    page: 1,
    pageSize: 20,
    total: 0,
    items: [],
  });
  const { wrapper } = await setup();
  await wrapper.get("input").setValue("尚未保存的新标题");
  const submit = wrapper
    .findAll("button")
    .find((button) => button.text() === "提交来源审核")!;
  const parse = wrapper
    .findAll("button")
    .find((button) => button.text() === "重新解析资料")!;
  expect(submit.attributes("disabled")).toBeDefined();
  expect(parse.attributes("disabled")).toBeDefined();
  expect(wrapper.text()).toContain("请先保存当前修改");
  wrapper.unmount();
});
