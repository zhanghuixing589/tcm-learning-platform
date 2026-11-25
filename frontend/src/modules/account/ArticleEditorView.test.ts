import { afterEach, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import { createPinia } from "pinia";
import { h } from "vue";
import { createMemoryHistory, createRouter, RouterView } from "vue-router";
import { AxiosError, AxiosHeaders } from "axios";
import { api } from "../../shared/api/client";
import { useSessionStore } from "../../shared/stores/session";
import { testArticle, testPolicy } from "../community/testFixtures";
import ArticleEditorView from "./ArticleEditorView.vue";

afterEach(() => vi.restoreAllMocks());
it("does not overwrite a newly opened article when an earlier save resolves late", async () => {
  const article = testArticle;
  const first = article("11", "第一篇"),
    second = article("22", "第二篇");
  vi.spyOn(api.http, "get").mockImplementation(async (url) => ({
    data: { data: url === "/community/text-policy" ? testPolicy
      : url === "/community/sections" ? []
      : String(url).endsWith("22") ? second : first },
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
      { path: "/contribute", component: { render: () => h("div") } },
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

async function mountArticle(id = "new") {
  const pinia = createPinia();
  useSessionStore(pinia).user = {
    id: "1", username: "writer", nickname: "作者", bio: null, status: "ACTIVE",
    roles: ["CONTRIBUTOR"], permissions: ["community:article:submit"], createdAt: "",
  };
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/account/articles/:articleId", component: ArticleEditorView },
      { path: "/account", component: { render: () => h("div") } },
      { path: "/contribute", component: { render: () => h("div") } },
    ],
  });
  await router.push("/account/articles/" + id);
  const wrapper = mount({ render: () => h(RouterView) }, { global: { plugins: [pinia, router] } });
  await flushPromises();
  return { wrapper, router };
}

it("saves the exact Markdown source, including trailing whitespace and supplementary characters", async () => {
  vi.spyOn(api.http, "get").mockImplementation(async (url) => ({
    data: { data: url === "/community/text-policy" ? testPolicy : [] },
  }));
  const source = "    😀\n\n带换行  \n";
  const post = vi.spyOn(api.http, "post").mockResolvedValue({ data: { data: testArticle("33", "文题", source) } });
  const { wrapper } = await mountArticle();
  await wrapper.get("#article-title").setValue("文题");
  await wrapper.get("#article-body").setValue(source);
  await wrapper.get("form").trigger("submit");
  await flushPromises();
  expect(post).toHaveBeenCalledWith("/me/articles", expect.objectContaining({ body: source, sourceFormat: "MARKDOWN" }),
    expect.objectContaining({ headers: { "Idempotency-Key": expect.any(String) } }));
  wrapper.unmount();
});

it("keeps every character and the leave warning after creating a private identity and refreshing quota", async () => {
  const identity = testArticle("33", "长文", "");
  const quota = { ...identity.quota, bodyCharLimit: 120000 };
  vi.spyOn(api.http, "get").mockImplementation(async (url) => ({
    data: { data: url === "/community/text-policy" ? testPolicy
      : url === "/me/articles/33/quota" ? quota
      : String(url).endsWith("/quota-requests") ? { items: [], total: 0, page: 1, pageSize: 20 }
      : url === "/me/articles/33" ? identity : [] },
  }));
  const post = vi.spyOn(api.http, "post").mockResolvedValue({ data: { data: identity } });
  const patch = vi.spyOn(api.http, "patch").mockResolvedValue({
    data: { data: { ...testArticle("33", "长文", "中".repeat(100000) + "😀"), quota, lockVersion: 1 } },
  });
  const confirm = vi.spyOn(window, "confirm").mockReturnValue(false);
  const source = "中".repeat(100000) + "😀";
  const { wrapper, router } = await mountArticle();
  await wrapper.get("#article-title").setValue("长文");
  await wrapper.get("#article-body").setValue(source);
  expect(wrapper.text()).toContain("100001");
  await wrapper.get("[data-create-draft-identity]").trigger("click");
  await flushPromises();
  expect(post).toHaveBeenCalledWith("/me/articles/draft-identities", { title: "长文", sourceFormat: "MARKDOWN" }, expect.any(Object));
  expect(router.currentRoute.value.path).toBe("/account/articles/33");
  expect((wrapper.get("#article-body").element as HTMLTextAreaElement).value).toBe(source);
  expect(wrapper.text()).not.toContain("草稿已保存");
  await wrapper.get("[data-refresh-quota]").trigger("click");
  await flushPromises();
  expect(wrapper.text()).toContain("120000");
  expect((wrapper.get("#article-body").element as HTMLTextAreaElement).value).toBe(source);
  await router.push("/account");
  expect(confirm).toHaveBeenCalled();
  expect(router.currentRoute.value.path).toBe("/account/articles/33");
  await wrapper.get("form").trigger("submit");
  await flushPromises();
  expect(patch).toHaveBeenCalledWith("/me/articles/33", expect.objectContaining({
    body: source, baseRevisionId: "33", lockVersion: 0,
  }));
  expect(wrapper.text()).toContain("草稿已保存");
  wrapper.unmount();
});

it.each([
  [403, "权限已发生变化"],
  [409, "核对最新状态"],
  [429, "22 秒后重试"],
])("preserves edited source when the server responds %s", async (status, warning) => {
  const article = testArticle("33", "保留草稿");
  vi.spyOn(api.http, "get").mockImplementation(async (url) => ({
    data: { data: url === "/community/text-policy" ? testPolicy : url === "/me/articles/33" ? article : [] },
  }));
  const headers = AxiosHeaders.from({ "retry-after": "22" });
  const response = { status, statusText: "", data: { message: "写入暂未完成" }, headers,
    config: { headers: new AxiosHeaders() } };
  vi.spyOn(api.http, "patch").mockRejectedValue(new AxiosError("write error", undefined, undefined, undefined, response));
  const { wrapper } = await mountArticle("33");
  await wrapper.get("#article-body").setValue("未保存的新内容\n");
  await wrapper.get("form").trigger("submit");
  await flushPromises();
  expect(wrapper.get("[role='alert']").text()).toContain(warning);
  expect((wrapper.get("#article-body").element as HTMLTextAreaElement).value).toBe("未保存的新内容\n");
  expect(wrapper.text()).toContain("当前修改尚未保存");
  if (status === 409) {
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(false);
    await wrapper.findAll("button").find((button) => button.text() === "重新载入稿件")!.trigger("click");
    expect(confirm).toHaveBeenCalledTimes(1);
    expect((wrapper.get("#article-body").element as HTMLTextAreaElement).value).toBe("未保存的新内容\n");
  }
  wrapper.unmount();
});

it("keeps the in-review source frozen and withdraws with its optimistic version lock", async () => {
  const draft = testArticle("33", "待审核", "  待审核正文\n");
  const article = { ...draft, revisionState: "IN_REVIEW", lockVersion: 4,
    latestRevision: { ...draft.latestRevision, state: "IN_REVIEW" },
    capabilities: { ...draft.capabilities, canEdit: false, canWithdraw: true, canDelete: false } };
  vi.spyOn(api.http, "get").mockImplementation(async (url) => ({
    data: { data: url === "/community/text-policy" ? testPolicy : url === "/me/articles/33" ? article : [] },
  }));
  const post = vi.spyOn(api.http, "post").mockResolvedValue({ data: { data: {
    ...article, revisionState: "WITHDRAWN", lockVersion: 5,
    latestRevision: { ...article.latestRevision, state: "WITHDRAWN" },
    capabilities: { ...article.capabilities, canWithdraw: false, canRevise: true },
  } } });
  const patch = vi.spyOn(api.http, "patch");
  const { wrapper } = await mountArticle("33");
  expect(wrapper.get("#article-body").attributes("readonly")).toBeDefined();
  await wrapper.get("form").trigger("submit");
  expect(patch).not.toHaveBeenCalled();
  const withdraw = wrapper.findAll("button").find((button) => button.text() === "撤回审核")!;
  await withdraw.trigger("click");
  await flushPromises();
  expect(post).toHaveBeenCalledWith("/me/articles/33/withdraw", { baseRevisionId: "33", lockVersion: 4 }, expect.any(Object));
  wrapper.unmount();
});

it("preserves a legacy draft's plain-text format on ordinary save", async () => {
  const draft = testArticle("33", "历史稿件", "# 纯文本");
  const article = { ...draft, sourceFormat: "PLAIN_TEXT",
    latestRevision: { ...draft.latestRevision, sourceFormat: "PLAIN_TEXT" } };
  vi.spyOn(api.http, "get").mockImplementation(async (url) => ({
    data: { data: url === "/community/text-policy" ? testPolicy : url === "/me/articles/33" ? article : [] },
  }));
  const patch = vi.spyOn(api.http, "patch").mockResolvedValue({ data: { data: article } });
  const { wrapper } = await mountArticle("33");
  await wrapper.get("#article-title").setValue("保留纯文本的新标题");
  await wrapper.get("form").trigger("submit");
  await flushPromises();
  expect(patch).toHaveBeenCalledWith("/me/articles/33", expect.objectContaining({ sourceFormat: "PLAIN_TEXT", body: "# 纯文本" }));
  wrapper.unmount();
});

it("sends a complete stable-key permutation, then applies the returned source and lock together", async () => {
  const first = { referenceKey: "376f0571-0706-410e-9041-3d95160dae85", displayNumber: 1, title: "甲",
    authors: null, publicationYear: null, sourceName: null, doi: null, url: null, locationLabel: null };
  const second = { ...first, referenceKey: "376f0571-0706-410e-9041-3d95160dae86", displayNumber: 5, title: "乙" };
  const draft = testArticle("33", "文献草稿", "甲[1]，乙[5]");
  const refs = [first, second];
  const article = { ...draft, references: refs, lockVersion: 3, latestRevision: { ...draft.latestRevision, references: refs } };
  const newRefs = [{ ...second, displayNumber: 1 }, { ...first, displayNumber: 2 }];
  const updated = { ...article, body: "甲[2]，乙[1]", lockVersion: 4, references: newRefs,
    latestRevision: { ...article.latestRevision, body: "甲[2]，乙[1]", references: newRefs } };
  vi.spyOn(api.http, "get").mockImplementation(async (url) => ({
    data: { data: url === "/community/text-policy" ? testPolicy : url === "/me/articles/33" ? article : [] },
  }));
  const post = vi.spyOn(api.http, "post").mockResolvedValue({ data: { data: updated } });
  const patch = vi.spyOn(api.http, "patch").mockResolvedValue({ data: { data: updated } });
  const { wrapper } = await mountArticle("33");
  await wrapper.get("[aria-label='上移 乙']").trigger("click");
  await wrapper.findAll("button").find((button) => button.text() === "确认重排与更新引用")!.trigger("click");
  await flushPromises();
  expect(post).toHaveBeenCalledWith("/me/articles/33/references/reorder", {
    baseRevisionId: "33", lockVersion: 3, referenceKeys: [second.referenceKey, first.referenceKey],
  }, expect.any(Object));
  expect((wrapper.get("#article-body").element as HTMLTextAreaElement).value).toBe(updated.body);
  await wrapper.get("#article-title").setValue("不修改文献");
  await wrapper.get("form").trigger("submit");
  await flushPromises();
  const input = patch.mock.calls[0][1];
  expect(input).toMatchObject({ lockVersion: 4, body: updated.body });
  expect(input).not.toHaveProperty("references");
  wrapper.unmount();
});

it.each([
  ["普通草稿", "本标签页的正文\n", false],
  ["私有身份", "中".repeat(100001), true],
])("keeps the server route and source when creation of %s returns an advanced draft", async (_name, source, identity) => {
  const latest = { ...testArticle("33", "其他标签页已保存", "服务器上的当前正文"), lockVersion: 1 };
  vi.spyOn(api.http, "get").mockImplementation(async (url) => ({
    data: { data: url === "/community/text-policy" ? testPolicy
      : url === "/me/articles/33" ? latest : [] },
  }));
  vi.spyOn(api.http, "post").mockResolvedValue({ data: { data: latest } });
  const confirm = vi.spyOn(window, "confirm").mockReturnValue(false);
  const { wrapper, router } = await mountArticle();
  await wrapper.get("#article-title").setValue("长文");
  await wrapper.get("#article-body").setValue(source);
  if (identity) await wrapper.get("[data-create-draft-identity]").trigger("click");
  else await wrapper.get("form").trigger("submit");
  await flushPromises();
  expect(router.currentRoute.value.path).toBe("/account/articles/33");
  expect(wrapper.text()).toContain("请重新载入最新版本");
  expect(wrapper.text()).not.toContain("草稿已保存");
  expect((wrapper.get("#article-body").element as HTMLTextAreaElement).value).toBe(source);
  const refresh = wrapper.findAll("button").find((button) => button.text() === "重新载入稿件")!;
  await refresh.trigger("click");
  expect(confirm).toHaveBeenCalledTimes(1);
  expect((wrapper.get("#article-body").element as HTMLTextAreaElement).value).toBe(source);
  confirm.mockReturnValue(true);
  await refresh.trigger("click");
  await flushPromises();
  expect((wrapper.get("#article-body").element as HTMLTextAreaElement).value).toBe(latest.body);
  wrapper.unmount();
});

it("refreshes quota request capability after a pending request is decided without reassigning source", async () => {
  const draft = testArticle("33", "保留编辑");
  const pending = { id: "41", resourceId: draft.id, requestedBy: "1", requestedLimit: 150000,
    reason: "篇幅", status: "PENDING", reviewerId: null, approvedLimit: null, reviewNote: null,
    lockVersion: 0, createdAt: "", reviewedAt: null } as const;
  const initial = { ...draft, quota: { ...draft.quota, latestRequest: pending },
    capabilities: { ...draft.capabilities, canRequestQuota: false } };
  const decided = { ...draft.quota, latestRequest: { ...pending, status: "REJECTED", reviewerId: "2",
    reviewNote: "请再次说明篇幅需求", lockVersion: 1 } };
  let settled = false;
  vi.spyOn(api.http, "get").mockImplementation(async (url) => ({
    data: { data: url === "/community/text-policy" ? testPolicy
      : url === "/me/articles/33" ? (settled ? { ...draft, quota: decided } : initial)
      : url === "/me/articles/33/quota" ? decided : [] },
  }));
  const { wrapper } = await mountArticle("33");
  const source = "中".repeat(100001);
  await wrapper.get("#article-body").setValue(source);
  expect(wrapper.text()).not.toContain("提交扩容申请");
  settled = true;
  await wrapper.get("[data-refresh-quota]").trigger("click");
  await flushPromises();
  expect(wrapper.text()).toContain("提交扩容申请");
  expect((wrapper.get("#article-body").element as HTMLTextAreaElement).value).toBe(source);
  expect(wrapper.text()).toContain("当前修改尚未保存");
  wrapper.unmount();
});

it('tracks top server-draft saving, failure, success and later unsaved edits',async()=>{
 vi.spyOn(api.http,'get').mockImplementation(async url=>({data:{data:url==='/community/text-policy'?testPolicy:[]}}));
 let rejectSave!: (error: Error)=>void;
 const post=vi.spyOn(api.http,'post').mockImplementationOnce(()=>new Promise((_resolve,reject)=>{rejectSave=reject;}));
 const {wrapper}=await mountArticle();
 expect(wrapper.get('.server-draft-status').text()).toContain('未保存到服务器');
 await wrapper.get('#article-title').setValue('保存状态');await wrapper.get('#article-body').setValue('真实正文');
 await wrapper.get('button[aria-label="保存草稿（顶部）"]').trigger('click');
 expect(wrapper.get('.server-draft-status').text()).toContain('正在保存到服务器');
 expect(wrapper.get('button[aria-label="保存草稿（顶部）"]').attributes('disabled')).toBeDefined();
 rejectSave(new Error('offline'));await flushPromises();expect(wrapper.get('.server-draft-status').text()).toContain('保存失败');expect((wrapper.get('#article-body').element as HTMLTextAreaElement).value).toBe('真实正文');
 post.mockResolvedValue({data:{data:testArticle('33','保存状态','真实正文')}});
 await wrapper.get('button[aria-label="保存草稿（顶部）"]').trigger('click');await flushPromises();
 expect(wrapper.get('.server-draft-status').text()).toContain('已保存到服务器');
 await wrapper.get('#article-body').setValue('之后的修改');expect(wrapper.get('.server-draft-status').text()).toContain('未保存到服务器');wrapper.unmount();
});
