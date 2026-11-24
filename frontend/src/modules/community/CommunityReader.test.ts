import { flushPromises, mount } from "@vue/test-utils";
import { createPinia } from "pinia";
import { createMemoryHistory, createRouter } from "vue-router";
import { afterEach, expect, it, vi } from "vitest";
import { api } from "../../shared/api/client";
import ResourceView from "../account/ResourceView.vue";
import CommunityDetailView from "./CommunityDetailView.vue";
import { testPolicy } from "./testFixtures";
import type { Post } from "./types";

afterEach(() => vi.restoreAllMocks());

const post: Post = {
  id: "42", resourceType: "ARTICLE", section: { id: "7", parentId: null, code: "legacy-content", name: "历史内容" },
  revisionId: "8", title: "公开文章", body: null, summary: null, sourceFormat: "MARKDOWN",
  formatPolicyId: "1", releaseMethod: "LEGACY", references: [],
  owner: { id: "3", nickname: "作者", avatarUrl: null },
  publishedAt: "2026-11-20T08:00:00Z", publicEditedAt: null, lastPublicCommentAt: null,
  commentCount: 0, likeCount: 0, bookmarkCount: 0, liked: false, bookmarked: false,
  commentsEnabled: true, pinned: false, pinLockVersion: 0,
  capabilities: { canEdit: false, canComment: false, canPin: false },
};
const body = "正文有 **学习笔记**。\n";

it.each([
  ["/resources/42", "/resources/:resourceId", "/resources/42", ResourceView],
  ["/community/posts/42", "/community/posts/:resourceId", "/posts/42", CommunityDetailView],
])("renders canonical source after one history read for %s", async (path, pattern, endpoint, component) => {
  const get = vi.spyOn(api.http, "get").mockImplementation(async (url) => {
    if (url === "/resources/42") return { data: { data: { body, community: post } } };
    if (url === "/posts/42") return { data: { data: { ...post, body } } };
    if (url === "/posts/42/comments") return { data: { data: { page: 1, pageSize: 20, total: 0, items: [] } } };
    if (url === "/community/text-policy") return { data: { data: testPolicy } };
    throw new Error("Unexpected path " + url);
  });
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: pattern, component }] });
  await router.push(path);
  const wrapper = mount(component, { global: { plugins: [createPinia(), router], stubs: { RouterLink: true } } });
  await flushPromises();
  expect(wrapper.get(".community-prose strong").text()).toBe("学习笔记");
  expect(get.mock.calls.filter(([url]) => ["/resources/42", "/posts/42"].includes(String(url))).map(([url]) => url))
    .toEqual([endpoint]);
  expect(get.mock.calls.filter(([url]) => url === "/posts/42/comments")).toHaveLength(1);
  wrapper.unmount();
});
