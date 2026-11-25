import { afterEach, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";
import { useSessionStore } from "../../shared/stores/session";
import { stageApi } from "../knowledge/api";
import { communityApi } from "../community/api";
import type { User, Page } from "../../shared/api/types";
import type { KnowledgeSummary } from "../knowledge/api";
import HomeContent from "./HomeContent.vue";
import type { Post } from "../community/types";

afterEach(() => vi.restoreAllMocks());
const user = { id: "1", nickname: "学习者", permissions: [] } as unknown as User;
function setup(signedIn = true) {
  const pinia = createPinia(); setActivePinia(pinia);
  const session = useSessionStore(); session.user = signedIn ? user : null;
  const wrapper = mount(HomeContent, { global: { plugins: [pinia], stubs: { RouterLink: { props: ["to"], template: '<a :href="to"><slot /></a>' } } } });
  return { wrapper, session };
}
it("does not request protected content for a guest", async () => {
  const knowledge = vi.spyOn(stageApi, "knowledge");
  const posts = vi.spyOn(communityApi, "posts");
  const { wrapper } = setup(false); await flushPromises();
  expect(knowledge).not.toHaveBeenCalled(); expect(posts).not.toHaveBeenCalled();
  expect(wrapper.text()).toContain("登录后阅读"); wrapper.unmount();
});
it("retains knowledge when the independent community preview fails", async () => {
  vi.spyOn(stageApi, "knowledge").mockResolvedValue({ items: [{ id: "11", title: "公开知识", type: "HERB", versionNo: 2, summary: "可核对的内容" }] } as Page<KnowledgeSummary>);
  vi.spyOn(communityApi, "posts").mockRejectedValue(new Error("offline"));
  const { wrapper } = setup(); await flushPromises();
  expect(wrapper.text()).toContain("公开知识"); expect(wrapper.text()).toContain("社区内容暂未加载");
  expect(wrapper.find('a[href="/knowledge/11"]').exists()).toBe(true); wrapper.unmount();
});
it("discards in-flight results after sign-out", async () => {
  let resolve!: (value: Page<KnowledgeSummary>) => void;
  vi.spyOn(stageApi, "knowledge").mockImplementation(() => new Promise((done) => { resolve = done; }));
  vi.spyOn(communityApi, "posts").mockResolvedValue({ items: [] } as never);
  const { wrapper, session } = setup();
  session.user = null; await flushPromises();
  resolve({ items: [{ id: "1", title: "旧会话内容", type: "HERB" }] } as Page<KnowledgeSummary>);
  await flushPromises();
  expect(wrapper.text()).not.toContain("旧会话内容"); expect(wrapper.text()).toContain("登录后阅读"); wrapper.unmount();
});
it("shows honest empty states without fabricated recommendations", async () => {
  vi.spyOn(stageApi, "knowledge").mockResolvedValue({ items: [] } as never);
  vi.spyOn(communityApi, "posts").mockResolvedValue({ items: [] } as never);
  const { wrapper } = setup(); await flushPromises();
  expect(wrapper.text()).toContain("知识目录正在积累"); expect(wrapper.text()).toContain("还没有公开内容"); wrapper.unmount();
});

it("renders community without waiting for an unresolved knowledge request", async () => {
  vi.spyOn(stageApi, "knowledge").mockImplementation(() => new Promise(() => {}));
  vi.spyOn(communityApi, "posts").mockResolvedValue({ items: [{id: "9", title: "社区先到", section: {name: "学习交流"}, owner: {nickname: "作者"}, resourceType: "ARTICLE", commentCount: 2, likeCount: 3, bookmarkCount: 1}] } as never);
  const { wrapper } = setup(); await flushPromises();
  expect(wrapper.text()).toContain("社区先到");
  expect(wrapper.text()).toContain("3 个赞"); wrapper.unmount();
});
it("retries knowledge without clearing or reloading the successful community", async () => {
  vi.spyOn(stageApi, "knowledge").mockRejectedValueOnce(new Error('offline')).mockResolvedValue({items: []} as never);
  const posts = vi.spyOn(communityApi, "posts").mockResolvedValue({items: []} as never);
  const {wrapper} = setup(); await flushPromises();
  await wrapper.get('.discovery-knowledge button').trigger('click'); await flushPromises();
  expect(posts).toHaveBeenCalledTimes(1);
  expect(wrapper.text()).toContain("知识目录正在积累"); wrapper.unmount();
});

it("discards both previews from a previous account after switching users", async () => {
  let oldKnowledge!: (value: Page<KnowledgeSummary>) => void;
  let oldPosts!: (value: Page<Post>) => void;
  vi.spyOn(stageApi, "knowledge").mockImplementationOnce(() => new Promise(done => { oldKnowledge = done; })).mockResolvedValue({ items: [] } as never);
  vi.spyOn(communityApi, "posts").mockImplementationOnce(() => new Promise(done => { oldPosts = done; })).mockResolvedValue({ items: [] } as never);
  const {wrapper, session} = setup();
  session.user = {...user, id: "2"}; await flushPromises();
  oldKnowledge({ items: [{ id: "1", title: "上一账号知识", type: "HERB" }] } as Page<KnowledgeSummary>);
  oldPosts({ items: [{ id: "8", title: "上一账号帖子", section: {name: "旧分区"}, owner: {id: "1", nickname: "旧作者"}, resourceType: "POST" }] } as Page<Post>);
  await flushPromises();
  expect(wrapper.text()).not.toContain("上一账号");
  expect(wrapper.text()).toContain("知识目录正在积累");
  expect(wrapper.text()).toContain("还没有公开内容"); wrapper.unmount();
});

it.each([
  ['MARKDOWN', '## 重点\n\n**学习** [来源](https://example.org) ![图注](https://example.org/image.webp)', '重点 学习 来源 图注'],
  ['PLAIN_TEXT', '# 原始标题 **原始内容**', '# 原始标题 **原始内容**'],
] as const)('uses the community excerpt rules for %s summaries and real author/interaction links', async (format, summary, text) => {
  vi.spyOn(stageApi, 'knowledge').mockResolvedValue({items: []} as never);
  vi.spyOn(communityApi, 'posts').mockResolvedValue({items: [{
    id: '9', resourceType: 'ARTICLE', title: '学习记录', summary, sourceFormat: format,
    section: {name: '学习交流'}, owner: {id: '7', nickname: '作者', avatarUrl: null},
    commentCount: 2, likeCount: 3, bookmarkCount: 1,
  }]} as never);
  const {wrapper} = setup(); await flushPromises();
  expect(wrapper.get('.home-post-summary').text()).toBe(text);
  expect(wrapper.find('.home-post-summary img').exists()).toBe(false);
  expect(wrapper.get('.home-post-author').attributes('href')).toBe('/users/7');
  expect(wrapper.get('.home-post-stats a').attributes('href')).toBe('/community/posts/9#community-comments-9');
  expect(wrapper.get('.home-post-stats').text()).toContain('3 个赞');
  expect(wrapper.get('.home-post-stats').text()).toContain('1 人收藏'); wrapper.unmount();
});
