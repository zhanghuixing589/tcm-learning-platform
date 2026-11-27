import { afterEach, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import { createPinia } from "pinia";
import { createMemoryHistory, createRouter } from "vue-router";
import { useSessionStore } from "../../shared/stores/session";
import { stageApi } from "./api";
import { api } from "../../shared/api/client";
import { testPolicy } from "../community/testFixtures";
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

it("renders the frozen article submission with its section and manual references", async () => {
  const value = {
    id: "8",
    revisionId: "9",
    documentId: null,
    title: "待审核文稿",
    kind: "ARTICLE" as const,
    submittedBy: "2",
    submitterName: "作者",
    reviewerId: null,
    status: "PENDING",
    reviewNote: null,
    lockVersion: 0,
    createdAt: "",
    events: [],
    article: {
      id: "6",
      revisionId: "9",
      title: "冻结的稿件",
      body: "冻结的 **参考材料**[1]",
      revisionState: "IN_REVIEW",
      sourceFormat: "MARKDOWN" as const,
      formatPolicyId: "1",
      sourceHash: "",
      versionNo: 2,
      sectionId: "4",
      section: { id: "4", parentId: null, name: "学习交流", code: "study" },
      references: [
        {
          referenceKey: "376f0571-0706-410e-9041-3d95160dae85",
          displayNumber: 1,
          title: "冻结的文献",
          authors: null,
          publicationYear: null,
          sourceName: null,
          doi: null,
          url: null,
          locationLabel: null,
        },
      ],
    },
  };
  vi.spyOn(stageApi, "review").mockResolvedValue(value);
  const requests = vi
    .spyOn(api.http, "get")
    .mockResolvedValue({ data: { data: testPolicy } });
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/reviews/:id", component: ReviewView },
      { path: "/reviews", component: { template: "<div />" } },
    ],
  });
  await router.push("/reviews/8");
  const wrapper = mount(ReviewView, {
    global: { plugins: [createPinia(), router] },
  });
  await flushPromises();
  expect(wrapper.text()).toContain("学习交流");
  expect(wrapper.get(".community-prose strong").text()).toBe("参考材料");
  expect(wrapper.get(".community-prose a").attributes("href")).toBe(
    "#reference-376f0571-0706-410e-9041-3d95160dae85",
  );
  expect(wrapper.get(".community-reference-list li").text()).toBe("冻结的文献");
  expect(requests.mock.calls.map(([path]) => path)).toEqual([
    "/community/text-policy",
  ]);
  wrapper.unmount();
});

it("requires explicit partial warnings and sends a fixed coverage snapshot for source approval", async () => {
  const pinia = createPinia();
  useSessionStore(pinia).user = {
    id: "1",
    permissions: ["knowledge:review"],
  } as never;
  const value = {
    id: "v2",
    kind: "SOURCE",
    title: "部分可用来源",
    submittedBy: "2",
    submitterName: "作者",
    reviewerId: "1",
    status: "PENDING",
    lockVersion: 4,
    events: [],
    document: {
      id: "s",
      title: "资料",
      sourceType: "BOOK",
      reviewState: "IN_REVIEW",
      availability: "ACTIVE",
      processingSummary: {
        overallOutcome: "PARTIAL_SUCCESS",
        stages: { text: "PARTIAL_SUCCESS" },
        warnings: [
          {
            code: "IMAGE_FAILED",
            message: "第2页图片缺失",
            severity: "WARNING",
          },
        ],
        coverageSnapshotHash: "snapshot",
        units: [
          { id: "u1", unitNo: 1, title: "可靠页", textReliable: true },
          { id: "u2", unitNo: 2, title: "扫描页", textReliable: false },
        ],
      },
    },
  };
  vi.spyOn(stageApi, "review").mockResolvedValue(value as never);
  const decide = vi
    .spyOn(stageApi, "decide")
    .mockResolvedValue({ ...value, status: "APPROVED" } as never);
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: "/reviews/:id", component: ReviewView }],
  });
  await router.push("/reviews/v2");
  const wrapper = mount(ReviewView, { global: { plugins: [pinia, router] } });
  await flushPromises();
  await wrapper.get("#review-note").setValue("核对覆盖");
  await wrapper.get("form").trigger("submit");
  expect(decide).not.toHaveBeenCalled();
  expect(wrapper.text()).toContain("请逐项核对并确认处理警告");
  await wrapper.get("input[type=checkbox]").setValue(true);
  const mode = wrapper
    .findAll("select")
    .find((s) => s.find('option[value="TEXT_CITABLE"]').exists())!;
  await mode.setValue("TEXT_CITABLE");
  const boxes = wrapper.findAll("input[type=checkbox]");
  expect(boxes[2]!.attributes("disabled")).toBeDefined();
  await boxes[1]!.setValue(true);
  await wrapper.get("form").trigger("submit");
  await flushPromises();
  expect(decide).toHaveBeenCalledWith("v2", 4, "APPROVED", "核对覆盖", {
    acknowledgedWarnings: ["IMAGE_FAILED"],
    approvedUse: "TEXT_CITABLE",
    approvedCoverage: { unitIds: ["u1"] },
    coverageSnapshotHash: "snapshot",
  });
  wrapper.unmount();
});
