import { afterEach, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import { createPinia } from "pinia";
import { createMemoryHistory, createRouter } from "vue-router";
import { useSessionStore } from "../../shared/stores/session";
import { stageApi } from "./api";
import ReviewView from "./ReviewView.vue";

afterEach(() => vi.restoreAllMocks());
async function page(submittedBy: string) {
  const pinia = createPinia();
  useSessionStore(pinia).user = {
    id: "1",
    username: "reviewer",
    nickname: "审核者",
    bio: null,
    status: "ACTIVE",
    roles: ["REVIEWER"],
    permissions: ["knowledge:review"],
    createdAt: "",
  };
  const value = {
    id: "8",
    revisionId: null,
    documentId: "5",
    title: "学习资料",
    kind: "SOURCE" as const,
    submittedBy,
    submitterName: "作者",
    reviewerId: "1",
    status: "PENDING",
    reviewNote: null,
    lockVersion: 7,
    createdAt: "",
    events: [],
  };
  vi.spyOn(stageApi, "review").mockResolvedValue(value);
  const decide = vi
    .spyOn(stageApi, "decide")
    .mockResolvedValue({ ...value, status: "APPROVED" });
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/reviews/:id", component: ReviewView },
      { path: "/reviews", component: { template: "<div />" } },
    ],
  });
  await router.push("/reviews/8");
  const wrapper = mount(ReviewView, { global: { plugins: [pinia, router] } });
  await flushPromises();
  return { wrapper, decide };
}
it("blocks self review even when a task was already assigned to the current user", async () => {
  const { wrapper, decide } = await page("1");
  expect(wrapper.text()).toContain("不能审核本人提交的内容");
  expect(wrapper.find("form").exists()).toBe(false);
  expect(decide).not.toHaveBeenCalled();
  wrapper.unmount();
});
it("requires a review note and sends the currently displayed optimistic lock version", async () => {
  const { wrapper, decide } = await page("2");
  await wrapper.get("form").trigger("submit");
  expect(decide).not.toHaveBeenCalled();
  await wrapper.get("#review-note").setValue("已核对原文与书目信息");
  await wrapper.get("form").trigger("submit");
  await flushPromises();
  expect(decide).toHaveBeenCalledWith(
    "8",
    7,
    "APPROVED",
    "已核对原文与书目信息",
  );
  wrapper.unmount();
});
