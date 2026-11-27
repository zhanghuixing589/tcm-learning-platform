import { afterEach, expect, it, vi } from "vitest";
import { mount, flushPromises } from "@vue/test-utils";
import { createRouter, createMemoryHistory } from "vue-router";
import SourceWizardView from "./SourceWizardView.vue";
import { stageApi } from "./api";
import { readerApi } from "./readerApi";
afterEach(() => vi.restoreAllMocks());
async function setup() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/contribute/sources/:id", component: SourceWizardView },
      { path: "/sources/:id", component: { template: "<div />" } },
    ],
  });
  await router.push("/contribute/sources/new");
  const wrapper = mount(SourceWizardView, { global: { plugins: [router] } });
  await flushPromises();
  return { wrapper, router };
}
it("accepts DOC, keeps metadata editable and saves without starting processing", async () => {
  vi.spyOn(stageApi, "upload").mockResolvedValue({
    id: "f",
    originalName: "中医.doc",
  } as never);
  vi.spyOn(readerApi, "metadata").mockResolvedValue({
    title: "自动标题",
    author: "作者",
    rightsText: "扫描到的版权文字",
    warnings: [],
  });
  const save = vi
    .spyOn(stageApi, "saveSource")
    .mockResolvedValue({ id: "s", lockVersion: 2 } as never);
  const confirm = vi.spyOn(readerApi, "confirm");
  const { wrapper } = await setup();
  expect(wrapper.get("input[type=file]").attributes("accept")).toContain(
    ".doc,",
  );
  Object.defineProperty(wrapper.get("input[type=file]").element, "files", {
    value: [new File(["doc"], "中医.doc")],
  });
  await wrapper.get("input[type=file]").trigger("change");
  await flushPromises();
  await wrapper.get('input[maxlength="255"]').setValue("人工核对标题");
  const button = wrapper
    .findAll("button")
    .find((b) => b.text() === "仅保存书目草稿")!;
  await button.trigger("click");
  await flushPromises();
  expect(save).toHaveBeenCalledWith(
    null,
    expect.objectContaining({
      title: "人工核对标题",
      fileId: "f",
      rights: expect.objectContaining({
        licenseType: "UNKNOWN",
        publicReadingAllowed: false,
        publicDownloadAllowed: false,
        quoteDisplayAllowed: false,
        ragUseAllowed: false,
      }),
    }),
  );
  expect(confirm).not.toHaveBeenCalled();
  wrapper.unmount();
});
it("sends only editable input fields when saving a loaded source", async () => {
  vi.spyOn(stageApi,"source").mockResolvedValue({id:"s",title:"现有书目",sourceType:"BOOK",fileId:"f",lockVersion:4,reviewState:"DRAFT",processingJob:{id:"j",status:"SUCCEEDED"},readingEditionId:"e",rightsStatus:"UNCONFIRMED"} as never);
  const save=vi.spyOn(stageApi,"saveSource").mockResolvedValue({id:"s",lockVersion:5} as never);
  const {wrapper,router}=await setup();await router.push('/contribute/sources/s');await flushPromises();
  await wrapper.findAll('button').find(b=>b.text()==='仅保存书目草稿')!.trigger('click');await flushPromises();
  const payload=save.mock.calls[0]![1];expect(payload).toMatchObject({title:'现有书目',fileId:'f',expectedVersion:4});
  for(const key of ['id','lockVersion','reviewState','processingJob','readingEditionId','rightsStatus'])expect(payload).not.toHaveProperty(key);
  wrapper.unmount();
});
it("does not apply an upload completed after navigating to a different source", async () => {
  let complete!: (value: never) => void;
  vi.spyOn(stageApi, "upload").mockImplementation(
    () => new Promise((resolve) => (complete = resolve)),
  );
  vi.spyOn(stageApi, "source").mockResolvedValue({
    id: "b",
    title: "后打开的资料",
    sourceType: "BOOK",
    fileId: "old",
    lockVersion: 3,
  } as never);
  const metadata = vi.spyOn(readerApi, "metadata");
  const { wrapper, router } = await setup();
  Object.defineProperty(wrapper.get("input[type=file]").element, "files", {
    value: [new File(["txt"], "a.txt")],
  });
  await wrapper.get("input[type=file]").trigger("change");
  await router.push("/contribute/sources/b");
  await flushPromises();
  complete({ id: "late", originalName: "a.txt" } as never);
  await flushPromises();
  expect(metadata).not.toHaveBeenCalled();
  expect(
    (wrapper.get('input[maxlength="255"]').element as HTMLInputElement).value,
  ).toBe("后打开的资料");
  wrapper.unmount();
});
