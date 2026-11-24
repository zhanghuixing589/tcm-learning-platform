import { flushPromises, mount } from "@vue/test-utils";
import { afterEach, expect, it, vi } from "vitest";
import { api } from "../../shared/api/client";
import CommunityPoliciesAdminView from "./CommunityPoliciesAdminView.vue";
import { testPolicy } from "./testFixtures";

afterEach(() => vi.restoreAllMocks());

async function openActivationWithRefreshedHead() {
  const selected = { policyId: "2", versionNo: 2, config: testPolicy.config, reason: "配置版本",
    createdBy: "4", createdAt: "2026-11-23T08:00:00Z" };
  let head = { ...testPolicy, lockVersion: 3 };
  vi.spyOn(api.http, "get").mockImplementation(async (path) => ({
    data: { data: path === "/community/text-policy" ? head
      : { items: [selected], page: 1, pageSize: 20, total: 1 } },
  }));
  const put = vi.spyOn(api.http, "put").mockRejectedValue(new Error("文本政策已更新，请刷新后重试"));
  const wrapper = mount(CommunityPoliciesAdminView);
  const click = (label: string) => {
    const button = wrapper.findAll("button").find((item) => item.text() === label);
    expect(button, `Expected a button labeled ${label}`).toBeDefined();
    return button!.trigger("click");
  };
  await flushPromises();
  await click("启用此版");
  await wrapper.get("textarea").setValue("核对通过");
  head = { ...head, policyId: "3", lockVersion: 4 };
  await click("刷新政策");
  await flushPromises();
  return { wrapper, click, put };
}

it("keeps the activation form tied to the head version the administrator originally checked", async () => {
  const { wrapper, put } = await openActivationWithRefreshedHead();
  await wrapper.get("form").trigger("submit");
  await flushPromises();
  expect(put).toHaveBeenCalledWith("/admin/community/text-policy-head",
    { policyId: "2", lockVersion: 3, reason: "核对通过" }, expect.any(Object));
  expect((wrapper.get("textarea").element as HTMLTextAreaElement).value).toBe("核对通过");
  wrapper.unmount();
});

it.each([
  { choice: "cancels", confirmed: false, lockVersion: 3 },
  { choice: "confirms", confirmed: true, lockVersion: 4 },
])("preserves the reason when the administrator $choice rechecking the current head", async ({ confirmed, lockVersion }) => {
  const { wrapper, click, put } = await openActivationWithRefreshedHead();
  const confirm = vi.spyOn(window, "confirm").mockReturnValue(confirmed);
  await click("重新核对当前政策");
  expect(confirm).toHaveBeenCalledOnce();
  expect(put).not.toHaveBeenCalled();
  expect((wrapper.get("textarea").element as HTMLTextAreaElement).value).toBe("核对通过");
  await wrapper.get("form").trigger("submit");
  await flushPromises();
  expect(put).toHaveBeenCalledWith("/admin/community/text-policy-head",
    { policyId: "2", lockVersion, reason: "核对通过" }, expect.any(Object));
  wrapper.unmount();
});
