import { flushPromises, mount } from "@vue/test-utils";
import { afterEach, expect, it, vi } from "vitest";
import { api } from "../../shared/api/client";
import SectionPanel from "./SectionPanel.vue";
import type { Section } from "./types";

afterEach(() => vi.restoreAllMocks());

const initial: Section = {
  id: "4", parentId: null, code: "study", name: "学习交流",
  description: "初版介绍", rulesText: "初版规则", ownerId: "8",
  lifecycle: "FORMAL", managementStatus: "MANAGED", active: true,
  writable: true, sortOrder: 0, lockVersion: 0, joined: true, memberCount: 1,
  capabilities: {
    canJoin: false, canLeave: false, canEditProfile: true, canManage: false,
    canPin: true, canPost: true, canSubmitArticle: false,
  },
};
const newest = { ...initial, rulesText: "管理员更新的规则", lockVersion: 1 };
const response = (data: Section) => ({ data: { data } });

it("keeps an owner's profile draft intact when they try to join while editing", async () => {
  const unjoined = {
    ...initial, joined: false, memberCount: 0,
    capabilities: { ...initial.capabilities, canJoin: true },
  };
  const saved = { ...unjoined, description: "待保存介绍", rulesText: "待保存规则", lockVersion: 1 };
  const put = vi.spyOn(api.http, "put").mockResolvedValue({ data: { data: { joined: true } } });
  vi.spyOn(api.http, "get").mockResolvedValue(response({ ...saved, joined: true }));
  const patch = vi.spyOn(api.http, "patch").mockResolvedValue(response(saved));
  const wrapper = mount(SectionPanel, { props: { sectionId: initial.id, initial: unjoined } });
  const button = (text: string) => wrapper.findAll("button").find((item) => item.text() === text)!;
  await button("编辑介绍与规则").trigger("click");
  await wrapper.findAll("textarea")[0].setValue(saved.description);
  await wrapper.findAll("textarea")[1].setValue(saved.rulesText);
  const join = button("加入分区");
  join.element.dispatchEvent(new MouseEvent("click", { bubbles: true }));
  await flushPromises();
  expect(put).not.toHaveBeenCalled();
  expect(join.element.disabled).toBe(true);
  expect(wrapper.findAll("textarea").map(({ element }) => element.value)).toEqual([saved.description, saved.rulesText]);
  await wrapper.get("form").trigger("submit");
  await flushPromises();
  expect(patch).toHaveBeenCalledWith(`/community/sections/${initial.id}/profile`, {
    lockVersion: 0, description: saved.description, rulesText: saved.rulesText,
  });
  await button("加入分区").trigger("click");
  await flushPromises();
  expect(put).toHaveBeenCalledOnce();
  wrapper.unmount();
});

it("preserves both typed fields and their original lock across a status refresh", async () => {
  const get = vi.spyOn(api.http, "get").mockResolvedValue(response(newest));
  const conflict = new Error("版本已更新，请刷新后重试");
  const patch = vi.spyOn(api.http, "patch").mockRejectedValue(conflict);
  const wrapper = mount(SectionPanel, { props: { sectionId: initial.id, initial } });
  const click = (text: string) => wrapper.findAll("button").find((button) => button.text() === text)!.trigger("click");
  await click("编辑介绍与规则");
  await wrapper.findAll("textarea")[0].setValue("未保存介绍");
  await wrapper.get("form").trigger("submit");
  await flushPromises();
  await click("刷新分区状态");
  await flushPromises();
  expect(get).toHaveBeenCalledTimes(1);
  expect(wrapper.text()).toContain(newest.rulesText);
  expect((wrapper.findAll("textarea")[0].element as HTMLTextAreaElement).value).toBe("未保存介绍");
  expect((wrapper.findAll("textarea")[1].element as HTMLTextAreaElement).value).toBe(initial.rulesText);
  await wrapper.get("form").trigger("submit");
  await flushPromises();
  expect(patch.mock.calls.map(([, input]) => input)).toEqual([
    { lockVersion: 0, description: "未保存介绍", rulesText: initial.rulesText },
    { lockVersion: 0, description: "未保存介绍", rulesText: initial.rulesText },
  ]);
  const confirm = vi.spyOn(window, "confirm").mockReturnValue(false);
  await click("载入最新配置");
  expect((wrapper.findAll("textarea")[0].element as HTMLTextAreaElement).value).toBe("未保存介绍");
  confirm.mockReturnValue(true);
  await click("载入最新配置");
  expect((wrapper.findAll("textarea")[1].element as HTMLTextAreaElement).value).toBe(newest.rulesText);
  patch.mockResolvedValue(response({ ...newest, lockVersion: 2, description: "重新编辑的介绍" }));
  await wrapper.findAll("textarea")[0].setValue("重新编辑的介绍");
  await wrapper.get("form").trigger("submit");
  await flushPromises();
  expect(patch.mock.calls[2][1]).toEqual({ lockVersion: 1, description: "重新编辑的介绍", rulesText: newest.rulesText });
  wrapper.unmount();
});

it("keeps a saved profile when an earlier status read resolves after the save", async () => {
  let resolveEarlier!: (value: ReturnType<typeof response>) => void;
  vi.spyOn(api.http, "get").mockImplementation(() => new Promise((resolve) => { resolveEarlier = resolve; }));
  vi.spyOn(api.http, "patch")
    .mockRejectedValueOnce(new Error("请求超时"))
    .mockResolvedValueOnce(response({ ...initial, description: "已保存介绍", lockVersion: 1 }));
  const wrapper = mount(SectionPanel, { props: { sectionId: initial.id, initial } });
  const click = (text: string) => wrapper.findAll("button").find((button) => button.text() === text)!.trigger("click");
  await click("编辑介绍与规则");
  await wrapper.findAll("textarea")[0].setValue("已保存介绍");
  await wrapper.get("form").trigger("submit");
  await flushPromises();
  await click("刷新分区状态");
  await flushPromises();
  expect(resolveEarlier).toBeTypeOf("function");
  await wrapper.get("form").trigger("submit");
  await flushPromises();
  resolveEarlier(response(initial));
  await flushPromises();
  expect(wrapper.text()).toContain("已保存介绍");
  expect(wrapper.text()).not.toContain("初版介绍");
  expect(wrapper.text()).not.toContain("正在读取分区规则");
  wrapper.unmount();
});
