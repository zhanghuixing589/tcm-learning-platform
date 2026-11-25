import { flushPromises, mount } from "@vue/test-utils";
import { createPinia } from "pinia";
import { afterEach, expect, it, vi } from "vitest";
import { createMemoryHistory, createRouter } from "vue-router";
import App from "./App.vue";
import ArticleEditorView from "./modules/account/ArticleEditorView.vue";
import DiscussionEditorView from "./modules/community/DiscussionEditorView.vue";
import { testArticle, testPolicy } from "./modules/community/testFixtures";
import { api } from "./shared/api/client";
import type { User } from "./shared/api/types";
import { useSessionStore } from "./shared/stores/session";

afterEach(() => vi.restoreAllMocks());

it.each([
  ["文章", "/account/articles/:articleId", "/account/articles/33", ArticleEditorView, "#article-body", { requiresAuth: true }],
  ["讨论", "/community/posts/new", "/community/posts/new", DiscussionEditorView, "#discussion-body", { permission: "community:post" }],
] as const)("removes the previous account's dirty %s editor on logout and identity switch", async (_label, path, target, editor, field, meta) => {
  const pinia = createPinia();
  const session = useSessionStore(pinia);
  const first: User = {
    id: "1", username: "author", nickname: "作者", bio: null, status: "ACTIVE", createdAt: "",
    roles: ["CONTRIBUTOR"], permissions: ["community:article:submit", "community:post"],
  };
  const second = { ...first, id: "2", username: "second", nickname: "另一账号" };
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path, component: editor, meta },
    { path: "/login", component: { template: "<p>登录页</p>" } },
    { path: "/account", component: { template: "<p>个人中心</p>" } },
    { path: "/community", component: { template: "<p>社区</p>" } },
    { path: "/contribute", component: { template: "<p>贡献工作台</p>" } },
    { path: "/account/articles/new", component: { template: "<p>文章发布入口</p>" } },
  ] });
  vi.spyOn(api.http, "get").mockImplementation(async (url) => {
    if (url === "/community/text-policy") return { data: { data: testPolicy } };
    if (url === "/community/sections") return { data: { data: [] } };
    if (url === "/me/articles/33" && session.user?.id === "1") {
      return { data: { data: testArticle("33", "上一账号的私有稿件", "上一账号的私有正文") } };
    }
    throw new Error("当前账号无法读取此稿件");
  });
  const confirm = vi.spyOn(window, "confirm").mockReturnValue(false);
  session.user = first;
  await router.push(target);
  const wrapper = mount(App, { global: { plugins: [pinia, router] } });
  await flushPromises();
  await wrapper.get(field).setValue("请勿跨账号显示的正文");
  await router.replace("/community");
  expect(router.currentRoute.value.path).toBe(target);
  expect(confirm).toHaveBeenCalledTimes(1);

  session.user = null;
  await flushPromises();
  expect(router.currentRoute.value.path).toBe("/login");
  expect(wrapper.find(field).exists()).toBe(false);
  expect(confirm).toHaveBeenCalledTimes(1);

  session.user = first;
  await router.replace(target);
  await flushPromises();
  await wrapper.get(field).setValue("上一账号尚未保存的正文");
  session.user = second;
  await flushPromises();
  const current = wrapper.find(field);
  expect(current.exists() ? (current.element as HTMLTextAreaElement).value : "").toBe("");
  expect(wrapper.text()).not.toContain("上一账号的私有稿件");
  expect(confirm).toHaveBeenCalledTimes(1);
  wrapper.unmount();
});
