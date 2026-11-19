import { afterEach, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import { createPinia } from "pinia";
import { createMemoryHistory, createRouter } from "vue-router";
import { api } from "../../shared/api/client";
import { stageApi, type KnowledgeDetail } from "./api";
import KnowledgeDetailView from "./KnowledgeDetailView.vue";

afterEach(() => vi.restoreAllMocks());
it("reads relations and bookmark state from one knowledge detail request", async () => {
  const item = {
    id: "11",
    revisionId: "12",
    type: "CONCEPT",
    canonicalName: "阴阳",
    title: "阴阳",
    body: "正文",
    summary: "正文",
    categoryId: null,
    categoryName: null,
    visibilityStatus: "PUBLISHED",
    revisionState: "APPROVED",
    versionNo: 1,
    createdAt: "",
    updatedAt: "",
    ownerId: "1",
    lockVersion: 0,
    aliases: [],
    evidence: [],
    ingredients: [],
    relations: [],
    reviewNote: null,
    bookmarked: true,
  } as KnowledgeDetail & { bookmarked: boolean };
  const detail = vi.spyOn(stageApi, "detail").mockResolvedValue(item);
  const relations = vi.spyOn(stageApi, "relations").mockResolvedValue([]);
  const resource = vi
    .spyOn(api.http, "get")
    .mockResolvedValue({ data: { data: { bookmarked: false } } });
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/knowledge/:id", component: KnowledgeDetailView },
      { path: "/knowledge", component: { template: "<div />" } },
    ],
  });
  await router.push("/knowledge/11");
  const wrapper = mount(KnowledgeDetailView, {
    global: { plugins: [createPinia(), router] },
  });
  await flushPromises();
  expect(detail).toHaveBeenCalledTimes(1);
  expect(relations).not.toHaveBeenCalled();
  expect(resource).not.toHaveBeenCalled();
  const button = wrapper.findAll("button").find((x) => x.text() === "已收藏");
  expect(button?.attributes("aria-pressed")).toBe("true");
  wrapper.unmount();
});
