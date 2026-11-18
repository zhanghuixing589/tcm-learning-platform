import { afterEach, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import { createPinia } from "pinia";
import { h } from "vue";
import { createMemoryHistory, createRouter, RouterView } from "vue-router";
import { api } from "../../shared/api/client";
import { useSessionStore } from "../../shared/stores/session";
import ArticleEditorView from "./ArticleEditorView.vue";

afterEach(() => vi.restoreAllMocks());
it("does not overwrite a newly opened article when an earlier save resolves late", async () => {
  const article = (id: string, title: string) => ({
    id,
    title,
    body: "已保存的正文",
    revisionId: id,
    revisionState: "DRAFT",
    visibilityStatus: "DRAFT",
    reviewNote: null,
  });
  const first = article("11", "第一篇"),
    second = article("22", "第二篇");
  vi.spyOn(api.http, "get").mockImplementation(async (url) => ({
    data: { data: String(url).endsWith("22") ? second : first },
  }));
  let resolveSave!: (value: any) => void;
  vi.spyOn(api.http, "patch").mockImplementation(
    () =>
      new Promise((resolve) => {
        resolveSave = resolve;
      }),
  );
  vi.spyOn(window, "confirm").mockReturnValue(true);
  const pinia = createPinia();
  useSessionStore(pinia).user = {
    id: "1",
    username: "writer",
    nickname: "作者",
    bio: null,
    status: "ACTIVE",
    roles: ["CONTRIBUTOR"],
    permissions: ["community:article:submit"],
    createdAt: "2026-11-18T00:00:00Z",
  };
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/account/articles/:articleId", component: ArticleEditorView },
      { path: "/account", component: { render: () => h("div") } },
    ],
  });
  await router.push("/account/articles/11");
  const wrapper = mount(
    { render: () => h(RouterView) },
    { global: { plugins: [pinia, router] } },
  );
  await flushPromises();
  await wrapper.get("#article-title").setValue("第一篇的新标题");
  await wrapper.get("form").trigger("submit");
  await flushPromises();
  expect(api.http.patch).toHaveBeenCalledTimes(1);
  await router.push("/account/articles/22");
  await flushPromises();
  expect(
    (wrapper.get("#article-title").element as HTMLInputElement).value,
  ).toBe("第二篇");
  resolveSave({ data: { data: { ...first, title: "第一篇的新标题" } } });
  await flushPromises();
  expect(
    (wrapper.get("#article-title").element as HTMLInputElement).value,
  ).toBe("第二篇");
  wrapper.unmount();
});
