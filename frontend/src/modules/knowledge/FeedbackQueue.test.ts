import { afterEach, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import { stageApi } from "./api";
import FeedbackQueue from "./FeedbackQueue.vue";

afterEach(() => vi.restoreAllMocks());
it("requests server-filtered feedback pages so older open reports remain reachable", async () => {
  const feedback = vi
    .spyOn(stageApi, "feedback")
    .mockImplementation(async (params) => ({
      page: Number(params?.page || 1),
      pageSize: 20,
      total: 120,
      items: [
        {
          id: params?.page === 2 ? "old" : "new",
          resourceId: "5",
          content: params?.page === 2 ? "较早的待处理纠错" : "最近的待处理纠错",
          status: "OPEN",
          resolution: null,
          createdAt: "",
        },
      ],
    }));
  const wrapper = mount(FeedbackQueue, {
    global: { stubs: { RouterLink: { template: "<a><slot /></a>" } } },
  });
  await flushPromises();
  expect(feedback).toHaveBeenLastCalledWith({
    status: "OPEN",
    page: 1,
    pageSize: 20,
  });
  await wrapper.get('button[aria-label="纠错下一页"]').trigger("click");
  await flushPromises();
  expect(feedback).toHaveBeenLastCalledWith({
    status: "OPEN",
    page: 2,
    pageSize: 20,
  });
  expect(wrapper.text()).toContain("较早的待处理纠错");
  wrapper.unmount();
});
