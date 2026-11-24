import { flushPromises, mount } from "@vue/test-utils";
import { afterEach, expect, it, vi } from "vitest";
import { AxiosError, AxiosHeaders } from "axios";
import { api } from "../../shared/api/client";
import { testPolicy } from "./testFixtures";
import CommentThread from "./CommentThread.vue";

afterEach(() => vi.restoreAllMocks());

const editableComment = {
  id: "21", resourceId: "1", rootCommentId: null, revisionId: "51", body: "原评论正文",
  author: { id: "2", nickname: "原评论作者", avatarUrl: null }, displayState: "VISIBLE",
  replyCount: 0, lockVersion: 0, capabilities: { canEdit: true, canReply: true },
};

function commentPage(item: unknown) {
  return { data: { data: { items: [item], page: 1, pageSize: 20, total: 1 } } };
}

function commentConflict() {
  return new AxiosError("write error", undefined, undefined, undefined, {
    status: 409, statusText: "", data: { message: "评论已更新" },
    headers: new AxiosHeaders(), config: { headers: new AxiosHeaders() },
  });
}

it.each([false, true])("requires explicit review of the refreshed edit baseline (reply: %s)", async (reply) => {
  const selected = reply ? { ...editableComment, id: "22", rootCommentId: "21" } : editableComment;
  const latest = { ...selected, revisionId: "52", lockVersion: 1, body: "另一个窗口保存的评论" };
  let latestOnServer = false;
  const get = vi.spyOn(api.http, "get").mockImplementation(async (_url, config) => {
    const params = config?.params as { rootId?: string } | undefined;
    if (reply && !params?.rootId) {
      return commentPage({ ...editableComment, body: "根评论", replyCount: 1, capabilities: {} });
    }
    return commentPage(latestOnServer ? latest : selected);
  });
  let rejectEdit!: (error: AxiosError) => void;
  const post = vi.spyOn(api.http, "post")
    .mockImplementationOnce(() => new Promise((_resolve, reject) => { rejectEdit = reject; }))
    .mockResolvedValueOnce({ data: { data: { ...latest, revisionId: "53", lockVersion: 2, body: "我的草稿" } } });
  const confirm = vi.spyOn(window, "confirm").mockReturnValue(false);
  const wrapper = mount(CommentThread, {
    props: { resourceId: "1", canComment: true, policy: testPolicy },
    global: { stubs: { RouterLink: true } },
  });
  const click = (label: string) => {
    const button = wrapper.findAll("button").find((item) => item.text() === label);
    expect(button, `Expected a button labeled ${label}`).toBeDefined();
    return button!.trigger("click");
  };
  await flushPromises();
  if (reply) {
    await wrapper.get("[data-replies='21']").trigger("click");
    await flushPromises();
  }
  await click("编辑");
  await wrapper.get("textarea").setValue("我的草稿");
  await wrapper.get("form").trigger("submit");
  latestOnServer = true;
  rejectEdit(commentConflict());
  await flushPromises();
  expect(post.mock.calls[0][1]).toEqual({ body: "我的草稿", baseRevisionId: "51", lockVersion: 0 });
  get.mockRejectedValueOnce(new Error("请求超时"));
  await click("刷新目标评论");
  await flushPromises();
  expect(wrapper.get("textarea").element.value).toBe("我的草稿");
  expect(wrapper.text()).toContain("请求超时");
  await click("刷新目标评论");
  await flushPromises();
  expect(get).toHaveBeenLastCalledWith("/posts/1/comments", { params: { page: 1, rootId: reply ? "21" : undefined } });
  expect(wrapper.get("form").text()).toContain(latest.body);
  await click("采用当前版本");
  await wrapper.get("form").trigger("submit");
  expect(post).toHaveBeenCalledOnce();
  expect(wrapper.get("textarea").element.value).toBe("我的草稿");
  confirm.mockReturnValue(true);
  await click("采用当前版本");
  await wrapper.get("form").trigger("submit");
  await flushPromises();
  expect(post.mock.calls[1][1]).toEqual({ body: "我的草稿", baseRevisionId: "52", lockVersion: 1 });
  expect(wrapper.text()).toContain("评论已更新");
  wrapper.unmount();
});

it.each([
  { state: "DELETED", action: "编辑", draft: "用户编辑的草稿" },
  { state: "HIDDEN", action: "编辑", draft: "" },
  { state: "HIDDEN", action: "回复", draft: "用户写的回复" },
])("scrubs a $state target and blocks further writes from the $action composer", async ({ state, action, draft }) => {
  const removed = { ...editableComment, displayState: state, body: null, author: null, revisionId: null, capabilities: {} };
  vi.spyOn(api.http, "get").mockResolvedValueOnce(commentPage(editableComment)).mockResolvedValueOnce(commentPage(removed));
  const post = vi.spyOn(api.http, "post").mockRejectedValueOnce(commentConflict());
  const wrapper = mount(CommentThread, {
    props: { resourceId: "1", canComment: true, policy: testPolicy },
    global: { stubs: { RouterLink: true } },
  });
  await flushPromises();
  await wrapper.findAll("button").find((button) => button.text() === action)!.trigger("click");
  if (draft) await wrapper.get("textarea").setValue(draft);
  await wrapper.get("form").trigger("submit");
  await flushPromises();
  const refresh = wrapper.findAll("button").find((button) => button.text() === "刷新目标评论");
  expect(refresh, "Expected a refresh action after a target conflict").toBeDefined();
  await refresh!.trigger("click");
  await flushPromises();
  expect(wrapper.text()).toContain("目标评论已不可用");
  expect(wrapper.text()).not.toMatch(/原评论正文|原评论作者/);
  expect(wrapper.get("textarea").element.value).toBe(draft);
  expect(wrapper.get("textarea").element.readOnly).toBe(true);
  await wrapper.get("form").trigger("submit");
  expect(post).toHaveBeenCalledOnce();
  wrapper.unmount();
});

it("scrubs unmodified prefilled text when a refreshed baseline later becomes unavailable", async () => {
  const latest = { ...editableComment, revisionId: "52", lockVersion: 1, body: "他处更新的评论" };
  const hidden = { ...latest, displayState: "HIDDEN", body: null, author: null, revisionId: null, capabilities: {} };
  vi.spyOn(api.http, "get")
    .mockResolvedValueOnce(commentPage(editableComment))
    .mockResolvedValueOnce(commentPage(latest))
    .mockResolvedValueOnce(commentPage(hidden));
  vi.spyOn(api.http, "post").mockRejectedValue(commentConflict());
  vi.spyOn(window, "confirm").mockReturnValue(true);
  const wrapper = mount(CommentThread, {
    props: { resourceId: "1", canComment: true, policy: testPolicy },
    global: { stubs: { RouterLink: true } },
  });
  const click = (text: string) => wrapper.findAll("button").find((item) => item.text() === text)!.trigger("click");
  await flushPromises();
  await click("编辑");
  await wrapper.get("form").trigger("submit");
  await flushPromises();
  await click("刷新目标评论");
  await flushPromises();
  await click("采用当前版本");
  expect(wrapper.get("textarea").element.value).toBe(editableComment.body);
  await wrapper.get("form").trigger("submit");
  await flushPromises();
  await click("刷新目标评论");
  await flushPromises();
  expect(wrapper.text()).toContain("目标评论已不可用");
  expect(wrapper.get("textarea").element.value).toBe("");
  wrapper.unmount();
});

it.each([false, true])("scrubs the target when ordinary paging sees it become hidden (pending write: %s)", async (pendingWrite) => {
  const latest = { ...editableComment, revisionId: "52", lockVersion: 1, body: "他处更新的评论" };
  const hidden = { ...latest, displayState: "HIDDEN", body: null, author: null, revisionId: null, capabilities: {} };
  let item: unknown = editableComment;
  vi.spyOn(api.http, "get").mockImplementation(async (_url, config) => {
    const { page } = config?.params as { page: number };
    const result = commentPage(page === 1 ? item : { ...editableComment, id: "22", body: "另一页评论", capabilities: {} });
    return { data: { data: { ...result.data.data, page, pageSize: 1, total: 2 } } };
  });
  let resolveWrite!: (response: { data: { data: typeof latest } }) => void;
  const post = vi.spyOn(api.http, "post").mockRejectedValueOnce(commentConflict());
  vi.spyOn(window, "confirm").mockReturnValue(true);
  const wrapper = mount(CommentThread, {
    props: { resourceId: "1", canComment: true, policy: testPolicy },
    global: { stubs: { RouterLink: true } },
  });
  const click = (text: string) => wrapper.findAll("button").find((item) => item.text() === text)!.trigger("click");
  await flushPromises();
  await click("编辑");
  await wrapper.get("form").trigger("submit");
  await flushPromises();
  item = latest;
  await click("刷新目标评论");
  await flushPromises();
  if (pendingWrite) {
    await click("采用当前版本");
    post.mockImplementationOnce(() => new Promise((resolve) => { resolveWrite = resolve; }));
    await wrapper.get("form").trigger("submit");
  }
  await click("下一页");
  await flushPromises();
  if (!pendingWrite) expect(wrapper.get("form").text()).toContain(latest.body);
  expect(wrapper.get("textarea").element.value).toBe(editableComment.body);
  item = hidden;
  await click("上一页");
  await flushPromises();
  if (pendingWrite) {
    resolveWrite({ data: { data: { ...latest, lockVersion: 2 } } });
    await flushPromises();
  }
  expect(wrapper.text()).toContain("目标评论已不可用");
  expect(wrapper.text()).not.toMatch(/他处更新的评论|原评论正文|原评论作者/);
  expect(wrapper.get("textarea").element.value).toBe("");
  wrapper.unmount();
});

it("keeps the specific reply target, source and command key on a rate-limited retry", async () => {
  const root = { id: "21", resourceId: "1", rootCommentId: null, body: "根评论",
    author: { id: "2", nickname: "甲", avatarUrl: null }, displayState: "VISIBLE",
    replyCount: 1, capabilities: { canReply: true } };
  const child = { ...root, id: "22", rootCommentId: "21", replyToCommentId: "21", body: "已有回复",
    author: { id: "3", nickname: "乙", avatarUrl: null }, replyCount: 0 };
  vi.spyOn(api.http, "get").mockImplementation(async (_url, config) => {
    const params = config?.params as { rootId?: string };
    return { data: { data: { page: 1, pageSize: 20, total: 1, items: [params.rootId ? child : root] } } };
  });
  const post = vi.spyOn(api.http, "post")
    .mockRejectedValueOnce(new AxiosError("write error", undefined, undefined, undefined, {
      status: 429, statusText: "", data: { message: "操作过于频繁" },
      headers: AxiosHeaders.from({ "retry-after": "22" }), config: { headers: new AxiosHeaders() },
    }))
    .mockResolvedValueOnce({ data: { data: { ...child, id: "23", replyToCommentId: "22" } } });
  const wrapper = mount(CommentThread, {
    props: { resourceId: "1", canComment: true, policy: testPolicy },
    global: { stubs: { RouterLink: true } },
  });
  await flushPromises();
  await wrapper.get("[data-replies='21']").trigger("click");
  await flushPromises();
  await wrapper.findAll(".community-replies button").find((button) => button.text() === "回复")!.trigger("click");
  await wrapper.get("textarea").setValue("保留  原字样\n");
  await wrapper.get("form").trigger("submit");
  await flushPromises();
  expect(wrapper.get("[role='alert']").text()).toContain("22 秒后重试");
  expect((wrapper.get("textarea").element as HTMLTextAreaElement).value).toBe("保留  原字样\n");
  await wrapper.get("form").trigger("submit");
  await flushPromises();
  expect(post).toHaveBeenCalledTimes(2);
  expect(post.mock.calls[0]).toEqual([
    "/posts/1/comments", { body: "保留  原字样\n", rootCommentId: "21", replyToCommentId: "22" },
    expect.objectContaining({ headers: { "Idempotency-Key": expect.any(String) } }),
  ]);
  expect(post.mock.calls[1]).toEqual(post.mock.calls[0]);
  expect(wrapper.text()).toContain("评论已发布");
  wrapper.unmount();
});

it("keeps a composed reply when focusing the same reply target again", async () => {
  const root = { id: "21", resourceId: "1", body: "原评论", author: { id: "2", nickname: "作者", avatarUrl: null },
    displayState: "VISIBLE", replyCount: 0, capabilities: { canReply: true } };
  vi.spyOn(api.http, "get").mockResolvedValue({ data: { data: { items: [root], page: 1, pageSize: 20, total: 1 } } });
  const wrapper = mount(CommentThread, {
    props: { resourceId: "1", canComment: true, policy: testPolicy },
    global: { stubs: { RouterLink: true } },
  });
  await flushPromises();
  const reply = wrapper.findAll("button").find((button) => button.text() === "回复")!;
  await reply.trigger("click");
  await wrapper.get("textarea").setValue("尚未发布的回复");
  await reply.trigger("click");
  expect((wrapper.get("textarea").element as HTMLTextAreaElement).value).toBe("尚未发布的回复");
  wrapper.unmount();
});

it("lets the owner remove a hidden comment without displaying its author or source", async () => {
  const root = { id: "21", resourceId: "1", body: "隐藏正文", author: { id: "2", nickname: "隐藏作者", avatarUrl: null },
    displayState: "HIDDEN", replyCount: 0, lockVersion: 3, capabilities: { canDelete: true } };
  vi.spyOn(api.http, "get").mockResolvedValue({ data: { data: { items: [root], page: 1, pageSize: 20, total: 1 } } });
  const remove = vi.spyOn(api.http, "delete").mockResolvedValue({ data: { data: {
    ...root, displayState: "DELETED", author: null, body: null, capabilities: {},
  } } });
  vi.spyOn(window, "confirm").mockReturnValue(true);
  const wrapper = mount(CommentThread, {
    props: { resourceId: "1", canComment: false, policy: testPolicy },
    global: { stubs: { RouterLink: true } },
  });
  await flushPromises();
  expect(wrapper.text()).not.toMatch(/隐藏正文|隐藏作者/);
  await wrapper.findAll("button").find((button) => button.text() === "删除评论")!.trigger("click");
  await flushPromises();
  expect(remove).toHaveBeenCalledWith("/comments/21", { params: { lockVersion: 3 } });
  expect(wrapper.text()).toContain("评论已删除");
  wrapper.unmount();
});

it.each([false, true])("keeps a confirmed deletion when an earlier page read returns late (reply: %s)", async (reply) => {
  const root = { id: reply ? "22" : "21", resourceId: "1", rootCommentId: reply ? "21" : null, revisionId: "51",
    body: "应隐藏的正文", author: { id: "2", nickname: "应隐藏的作者", avatarUrl: null }, displayState: "VISIBLE",
    replyCount: 0, lockVersion: 0, capabilities: { canEdit: true, canDelete: true } };
  const edited = { ...root, body: "仍应隐藏的正文", revisionId: "52", lockVersion: 1 };
  const deleted = { ...edited, displayState: "DELETED", body: null, author: null, capabilities: {} };
  const page = (item: unknown) => ({ data: { data: { items: [item], page: 1, pageSize: 20, total: 1 } } });
  let resolveEarlier!: (response: ReturnType<typeof page>) => void;
  let displayedPageReads = 0;
  vi.spyOn(api.http, "get").mockImplementation(async (_url, config) => {
    const params = config?.params as { rootId?: string };
    if (reply && !params.rootId) return page({
      ...root, id: "21", rootCommentId: null, body: "根评论", replyCount: 1,
      author: { id: "3", nickname: "根评论作者", avatarUrl: null }, capabilities: {},
    });
    displayedPageReads++;
    if (displayedPageReads === 1) return page(root);
    if (displayedPageReads === 2) return new Promise((resolve) => { resolveEarlier = resolve; });
    return page(deleted);
  });
  vi.spyOn(api.http, "post").mockResolvedValue({ data: { data: edited } });
  vi.spyOn(api.http, "delete").mockResolvedValue({ data: { data: deleted } });
  vi.spyOn(window, "confirm").mockReturnValue(true);
  const wrapper = mount(CommentThread, {
    props: { resourceId: "1", canComment: true, policy: testPolicy },
    global: { stubs: { RouterLink: true } },
  });
  await flushPromises();
  if (reply) {
    await wrapper.get("[data-replies='21']").trigger("click");
    await flushPromises();
  }
  await wrapper.findAll("button").find((button) => button.text() === "编辑")!.trigger("click");
  await wrapper.get("textarea").setValue(edited.body);
  await wrapper.get("form").trigger("submit");
  await flushPromises();
  expect(resolveEarlier).toBeTypeOf("function");
  await wrapper.findAll("button").find((button) => button.text() === "删除评论")!.trigger("click");
  await flushPromises();
  expect(wrapper.text()).toContain("评论已删除");
  resolveEarlier(page(edited));
  await flushPromises();
  expect(wrapper.text()).not.toMatch(/应隐藏的正文|应隐藏的作者/);
  expect(wrapper.get(reply ? ".community-replies .community-comment" : ".community-comment").text()).toContain("评论已删除");
  wrapper.unmount();
});

it("resets a composer for the deleted comment after confirmation", async () => {
  const root = { id: "21", resourceId: "1", rootCommentId: null, revisionId: "51",
    body: "应隐藏的正文", author: { id: "2", nickname: "应隐藏的作者", avatarUrl: null }, displayState: "VISIBLE",
    replyCount: 0, lockVersion: 0, capabilities: { canEdit: true, canDelete: true } };
  vi.spyOn(api.http, "get").mockResolvedValue({ data: { data: { items: [root], page: 1, pageSize: 20, total: 1 } } });
  vi.spyOn(api.http, "delete").mockResolvedValue({ data: { data: {
    ...root, displayState: "DELETED", author: null, body: null, capabilities: {},
  } } });
  vi.spyOn(window, "confirm").mockReturnValue(true);
  const wrapper = mount(CommentThread, {
    props: { resourceId: "1", canComment: true, policy: testPolicy },
    global: { stubs: { RouterLink: true } },
  });
  await flushPromises();
  await wrapper.findAll("button").find((button) => button.text() === "编辑")!.trigger("click");
  expect((wrapper.get("textarea").element as HTMLTextAreaElement).value).toBe(root.body);
  await wrapper.findAll("button").find((button) => button.text() === "删除评论")!.trigger("click");
  await flushPromises();
  expect((wrapper.get("textarea").element as HTMLTextAreaElement).value).toBe("");
  expect(wrapper.text()).toContain("写评论");
  wrapper.unmount();
});

it("renders a deleted root without author or source and pages its visible replies independently", async () => {
  const root = { id: "21", resourceId: "1", body: "<img src=x>", author: { id: "2", nickname: "隐藏作者", avatarUrl: null },
    displayState: "DELETED", replyCount: 2, capabilities: {} };
  vi.spyOn(api.http, "get").mockImplementation(async (_url, config) => {
    const params = config?.params as { rootId?: string; page: number };
    return {
      data: { data: params.rootId ? {
        page: params.page, pageSize: 1, total: 2,
        items: [{ ...root, id: "4" + params.page, rootCommentId: "21", displayState: "VISIBLE",
          author: { id: "3", nickname: "回复作者", avatarUrl: null }, body: "<b>回复" + params.page + "</b>", replyCount: 0 }],
      } : { page: 1, pageSize: 20, total: 1, items: [root] } },
    };
  });
  const wrapper = mount(CommentThread, {
    props: { resourceId: "1", canComment: false, policy: testPolicy },
    global: { stubs: { RouterLink: true } },
  });
  await flushPromises();
  expect(wrapper.text()).toContain("评论已删除");
  expect(wrapper.text()).not.toContain("隐藏作者");
  await wrapper.get("[data-replies='21']").trigger("click");
  await flushPromises();
  expect(wrapper.text()).toContain("<b>回复1</b>");
  expect(wrapper.find("b").exists()).toBe(false);
  await wrapper.get("[aria-label='回复分页'] button:last-child").trigger("click");
  await flushPromises();
  expect(wrapper.text()).toContain("<b>回复2</b>");
  expect(wrapper.text()).not.toContain("<b>回复1</b>");
  expect(api.http.get).toHaveBeenCalledTimes(3);
  expect(wrapper.find("textarea").exists()).toBe(false);
  wrapper.unmount();
});
