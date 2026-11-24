import { execFileSync } from "node:child_process";
import { createHash, randomUUID } from "node:crypto";
import { mkdirSync, readFileSync } from "node:fs";
import { expect, test as base, type BrowserContext, type Locator, type Page, type Response } from "@playwright/test";
import type { Article, Comment, PolicyHead, PolicyVersion, Post, OwnPost, Reference, Section, TextPolicy } from "../src/modules/community/types";
import type { Page as ResultPage } from "../src/shared/api/types";

type Credentials = { username: string; password: string };
type Actor = { context: BrowserContext; headers: Record<string, string>; page: Page };
const test = base.extend<{
  openActor: (credentials: Credentials) => Promise<Actor>;
  browserErrors: string[];
}>({
  browserErrors: async ({}, use) => {
    const errors: string[] = [];
    await use(errors);
    expect(errors).toEqual([]);
  },
  openActor: async ({ browser, baseURL, browserErrors }, use) => {
    const contexts: BrowserContext[] = [];
    try {
      await use(async (credentials) => {
        const context = await browser.newContext({ baseURL });
        contexts.push(context);
        const headers = await login(context, credentials);
        const page = await context.newPage();
        page.on("pageerror", (error) => browserErrors.push(error.message));
        return { context, headers, page };
      });
    } finally {
      for (const context of contexts) await context.close();
    }
  },
});

test.use({ actionTimeout: 15000, navigationTimeout: 30000 });

async function data<T>(response: Pick<Response, "status" | "json">, status = 200): Promise<T> {
  expect(response.status()).toBe(status);
  return (await response.json()).data as T;
}

async function login(context: BrowserContext, credentials: Credentials) {
  const { accessToken } = await data<{ accessToken: string }>(
    await context.request.post("/api/v1/auth/login", { data: credentials }),
  );
  return { Authorization: "Bearer " + accessToken };
}

async function register(context: BrowserContext, username: string, nickname: string) {
  const credentials = { username, password: "Stage1234!" };
  const { id } = await data<{ id: string }>(
    await context.request.post("/api/v1/auth/register", { data: { ...credentials, nickname } }), 201,
  );
  return { id, credentials };
}

function adminCredentials(): Credentials {
  return JSON.parse(readFileSync("../runtime/e2e-admin.json", "utf8").replace(/^\uFEFF/, ""));
}

function command(headers: Record<string, string>) {
  return { ...headers, "Idempotency-Key": randomUUID() };
}

function responseFor(page: Page, method: string, pathname: string) {
  return page.waitForResponse((response) => response.request().method() === method
    && new URL(response.url()).pathname === pathname);
}

async function capture(page: Page, name: string, region?: Locator, mobileRegion = region) {
  mkdirSync("../runtime/screenshots", { recursive: true });
  const original = page.viewportSize();
  for (const width of [1440, 375]) {
    await page.setViewportSize({ width, height: width === 1440 ? 1000 : 900 });
    await page.evaluate(() => window.scrollTo(0, 0));
    const target = width === 1440 ? region : mobileRegion;
    if (target) {
      await target.evaluate((element) => element.scrollIntoView({ block: "start" }));
    }
    expect(await page.evaluate(() => document.documentElement.scrollWidth),
      `${name} at ${width}px must fit the viewport`).toBeLessThanOrEqual(width);
    await page.screenshot({ path: `../runtime/screenshots/stage-three-${name}-${width}.png` });
  }
  if (original) await page.setViewportSize(original);
}

async function createSection(page: Page, name: string, code: string, options: { parentId?: string; ownerId?: string } = {}) {
  await page.getByRole("button", { name: "新建分区", exact: true }).click();
  await page.getByLabel("名称", { exact: true }).fill(name);
  await page.getByLabel("编码", { exact: true }).fill(code);
  if (options.parentId) await page.getByLabel("上级分区").selectOption(options.parentId);
  if (options.ownerId) await page.getByLabel("初始负责人账号 ID（选填）").fill(options.ownerId);
  await page.getByLabel("分区介绍", { exact: true }).fill("仅用于隔离环境的浏览器验收。");
  await page.getByLabel("分区规则", { exact: true }).fill("请使用合成示例，保留清晰的来源记录。");
  const created = responseFor(page, "POST", "/api/v1/admin/community/sections");
  await page.getByRole("button", { name: "创建分区", exact: true }).click();
  const section = await data<Section>(await created, 201);
  await expect(page.getByText("分区已创建。", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name, exact: true })).toBeVisible();
  expect(section).toMatchObject({ name, code, parentId: options.parentId ?? null, lifecycle: "FORMAL", writable: true });
  return section;
}

test("community sections, discussions, threaded comments and personal interactions use live services", async ({ context, openActor }) => {
  test.setTimeout(180000);
  const suffix = Date.now();
  const writer = await register(context, `discuss_${suffix}`, "验收分区负责人");
  const reader = await register(context, `thread_${suffix}`, "验收评论读者");
  const admin = await openActor(adminCredentials());
  const author = await openActor(writer.credentials);
  const visitor = await openActor(reader.credentials);
  const { page: authorPage } = author;
  const { page: readerPage } = visitor;

  const { parent, child } = await test.step("administrator establishes parent and child sections", async () => {
    await admin.page.goto("/admin/community/sections");
    const parent = await createSection(admin.page, `验收交流 ${suffix}`, `e2e-study-${suffix}`);
    const child = await createSection(admin.page, `验收读书笔记 ${suffix}`, `e2e-notes-${suffix}`,
      { parentId: parent.id, ownerId: writer.id });
    await capture(admin.page, "section-admin", undefined, admin.page.getByRole("group", { name: "分区设置" }));
    await readerPage.goto("/admin/community/sections");
    await expect(readerPage.getByRole("heading", { name: "暂时无法访问" })).toBeVisible();
    const denied = await visitor.context.request.post("/api/v1/admin/community/sections", {
      headers: command(visitor.headers), data: { name: "验收权限探测", code: `e2e-denied-${suffix}`, sortOrder: 0 },
    });
    expect(denied.status()).toBe(403);
    return { parent, child };
  });

  const sectionPath = `/community/sections/${child.id}`;
  const title = `验收记录 ${suffix}：怎样整理出处`;
  const source = [
    "**查找出处**时，先记下版本与页码。以下仅为浏览器验收的合成示例。",
    "", "1. 标记原文", "2. 写下阅读问题", "",
    "| 验收项 | 记录方式 | 章节 | 版次 | 日期 | 书库 | 位置 | 标签 | 人员 | 操作 | 备注 |",
    "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |",
    "| 出处 | 书名和页码 | 一 | 初 | 示例 | 馆藏 | 第十二页 | 阅读 | 作者 | 核对 | 合成示例 |", "",
    "```text", "literal_" + "x".repeat(110), "```",
  ].join("\n");
  const postId = await test.step("members join and leave, owner maintains section and publishes a discussion", async () => {
    await authorPage.goto(sectionPath);
    await expect(authorPage.getByText("0 位成员", { exact: true })).toBeVisible();
    await authorPage.getByRole("button", { name: "加入分区", exact: true }).click();
    await expect(authorPage.getByText("1 位成员，已加入", { exact: true })).toBeVisible();
    await expect(authorPage.getByRole("button", { name: "退出分区", exact: true })).toHaveCount(0);
    await readerPage.goto(sectionPath);
    await readerPage.getByRole("button", { name: "加入分区", exact: true }).click();
    await expect(readerPage.getByText("2 位成员，已加入", { exact: true })).toBeVisible();
    await readerPage.getByRole("checkbox", { name: "我的分区" }).check();
    await expect(readerPage.getByRole("navigation", { name: "社区分区" }).getByRole("link", { name: child.name })).toBeVisible();
    await readerPage.getByRole("button", { name: "退出分区", exact: true }).click();
    await expect(readerPage.getByText("1 位成员", { exact: true })).toBeVisible();
    await expect(readerPage.getByText("尚未加入分区。", { exact: true })).toBeVisible();
    await expect(readerPage.getByRole("button", { name: "编辑介绍与规则" })).toHaveCount(0);
    await authorPage.getByRole("button", { name: "编辑介绍与规则" }).click();
    await authorPage.getByLabel("分区规则", { exact: true }).fill("验收规则：写清合成示例的出处。");
    await authorPage.getByRole("button", { name: "保存分区介绍与规则" }).click();
    await expect(authorPage.getByText("验收规则：写清合成示例的出处。", { exact: true })).toBeVisible();
    await authorPage.getByRole("link", { name: "在本区发布讨论" }).click();
    await expect(authorPage.getByLabel("讨论分区")).toHaveValue(child.id);
    await authorPage.locator("#discussion-title").fill(title);
    await authorPage.locator("#discussion-body").fill(source);
    const creation = responseFor(authorPage, "POST", "/api/v1/posts");
    await authorPage.getByRole("button", { name: "发布讨论", exact: true }).click();
    const post = await data<Post>(await creation, 201);
    expect(post).toMatchObject({ title, body: source, releaseMethod: "DIRECT" });
    await expect(authorPage).toHaveURL(`/community/posts/${post.id}`);
    await authorPage.getByRole("button", { name: "在本区置顶" }).click();
    await expect(authorPage.getByRole("button", { name: "取消置顶" })).toBeVisible();
    await authorPage.getByRole("button", { name: /^点赞/ }).click();
    await expect(authorPage.getByRole("button", { name: "已点赞 0" })).toHaveAttribute("aria-pressed", "true");
    return post.id;
  });

  const path = `/community/posts/${postId}`;
  const commentsApi = `/api/v1/posts/${postId}/comments`;
  await test.step("reader searches and filters, reads Markdown and records one view", async () => {
    await readerPage.goto("/community");
    await readerPage.getByLabel("搜索社区内容").fill(title);
    await readerPage.getByLabel("内容类型").selectOption("ARTICLE");
    await readerPage.getByRole("button", { name: "应用筛选" }).click();
    await expect(readerPage.getByText("没有符合条件的内容。")).toBeVisible();
    await readerPage.getByLabel("内容类型").selectOption("POST");
    await readerPage.getByLabel("排序").selectOption("LATEST_REPLY");
    const filtered = responseFor(readerPage, "GET", "/api/v1/posts");
    await readerPage.getByRole("button", { name: "应用筛选" }).click();
    const filteredUrl = new URL((await filtered).url());
    expect(filteredUrl.searchParams.get("sort")).toBe("LATEST_REPLY");
    expect(filteredUrl.searchParams.get("type")).toBe("POST");
    const discussion = readerPage.getByRole("link", { name: title, exact: true });
    await expect(discussion).toBeVisible();
    await capture(readerPage, "community-list", undefined, readerPage.getByRole("region", { name: "社区内容" }));
    await readerPage.getByRole("navigation", { name: "社区分区" }).getByRole("link", { name: parent.name, exact: true }).click();
    await expect(discussion).toBeVisible();
    await readerPage.getByLabel("包含子分区").uncheck();
    await readerPage.getByRole("button", { name: "应用筛选" }).click();
    await expect(readerPage.getByText("没有符合条件的内容。")).toBeVisible();
    await readerPage.getByRole("navigation", { name: "社区分区" }).getByRole("link", { name: child.name, exact: true }).click();
    const reads: string[] = [];
    const recordRead = (request: import("@playwright/test").Request) => {
      const pathname = new URL(request.url()).pathname;
      if (request.method() === "GET" && [`/api/v1/posts/${postId}`, `/api/v1/resources/${postId}`].includes(pathname)) reads.push(pathname);
    };
    readerPage.on("request", recordRead);
    await discussion.click();
    await expect(readerPage).toHaveURL(path);
    await expect(readerPage.locator(".community-prose strong")).toHaveText("查找出处");
    await expect(readerPage.locator(".community-prose ol li")).toHaveCount(2);
    const table = readerPage.getByRole("table");
    await expect(table).toContainText("书名和页码");
    const code = readerPage.locator(".community-prose pre");
    await expect(code).toContainText("literal_" + "x".repeat(110));
    expect(await code.evaluate((element) => element.scrollWidth > element.clientWidth)).toBe(true);
    await expect(readerPage.getByRole("button", { name: "取消置顶" })).toHaveCount(0);
    await readerPage.getByRole("button", { name: "点赞 0" }).click();
    await expect(readerPage.getByRole("button", { name: "已点赞 1" })).toHaveAttribute("aria-pressed", "true");
    await readerPage.getByRole("button", { name: "收藏", exact: true }).click();
    await expect(readerPage.getByRole("button", { name: "已收藏", exact: true })).toHaveAttribute("aria-pressed", "true");
    const desktopViewport = readerPage.viewportSize()!;
    await readerPage.setViewportSize({ width: 375, height: 900 });
    const shortHeader = table.getByRole("columnheader", { name: "章节", exact: true });
    const headerLines = await shortHeader.evaluate((element) => {
      const text = document.createRange();
      text.selectNodeContents(element);
      return new Set(Array.from(text.getClientRects(), (rect) => rect.top)).size;
    });
    expect(headerLines, "a two-character table heading should fit on one line").toBe(1);
    for (const scroller of [table, code]) {
      expect(await scroller.evaluate((element) => {
        element.scrollLeft = element.scrollWidth;
        const isScrollable = element.scrollLeft > 0;
        element.scrollLeft = 0;
        return isScrollable;
      })).toBe(true);
    }
    await readerPage.setViewportSize(desktopViewport);
    await capture(readerPage, "community-detail");
    readerPage.off("request", recordRead);
    expect(reads).toEqual([`/api/v1/posts/${postId}`]);
    const history = await data<ResultPage<{ id: string }>>(await visitor.context.request.get("/api/v1/me/history", { headers: visitor.headers }));
    expect(history.total).toBe(1);
    expect(history.items[0].id).toBe(postId);
  });

  await test.step("comment edits, reply targeting, deleted placeholders and independent pagination", async () => {
    const rootText = "验收根评论 <b>仅是文本</b>";
    await readerPage.getByLabel("写评论").fill(rootText);
    const creation = responseFor(readerPage, "POST", commentsApi);
    await readerPage.getByRole("button", { name: "发布评论", exact: true }).click();
    const root = await data<Comment>(await creation, 201);
    const rootComment = readerPage.locator(".community-comment").filter({ hasText: rootText });
    await expect(rootComment).toBeVisible();
    await expect(rootComment.locator("b")).toHaveCount(0);
    await rootComment.getByRole("button", { name: "编辑", exact: true }).click();
    const updated = readerPage.getByLabel("编辑评论", { exact: true });
    await expect(updated).toBeFocused();
    const editedText = "验收根评论已更新";
    await updated.fill(editedText);
    await readerPage.getByRole("button", { name: "保存评论" }).click();
    const edited = readerPage.locator(".community-comment").filter({ hasText: editedText });
    await expect(edited).toContainText("已编辑");
    const secondRoot = await data<Comment>(await readerPage.context().request.post(commentsApi, {
      headers: command(visitor.headers), data: { body: "验收另一条评论串" },
    }), 201);

    await authorPage.reload();
    const otherRoot = authorPage.locator(".community-comment").filter({ hasText: editedText });
    await expect(otherRoot.getByRole("button", { name: "编辑", exact: true })).toHaveCount(0);
    await otherRoot.getByRole("button", { name: "回复", exact: true }).click();
    const replyText = "验收回复：出处记录清楚";
    await authorPage.getByLabel("回复 验收评论读者", { exact: true }).fill(replyText);
    const replyCreated = responseFor(authorPage, "POST", commentsApi);
    await authorPage.getByRole("button", { name: "发布回复", exact: true }).click();
    const reply = await data<Comment>(await replyCreated, 201);
    expect(reply).toMatchObject({ rootCommentId: root.id, replyToCommentId: root.id });
    await expect(authorPage.getByText(replyText, { exact: true })).toBeVisible();

    await readerPage.reload();
    const repliesButton = readerPage.locator(`[data-replies="${root.id}"]`);
    await repliesButton.click();
    await readerPage.locator(".community-comment").filter({ hasText: replyText })
      .getByRole("button", { name: "回复", exact: true }).click();
    const nestedText = "验收定向回复：补上页码";
    const nestedInput = readerPage.getByLabel("回复 验收分区负责人", { exact: true });
    await nestedInput.fill(nestedText);
    await test.step("same-post cross-thread reply returns live HTTP 400 and preserves the draft", async () => {
      const commentsUrl = (url: URL) => url.pathname === commentsApi;
      // Keep the UI's valid reply target and corrupt only the submitted root for this server validation check.
      await readerPage.route(commentsUrl, async (route) => {
        if (route.request().method() !== "POST") return route.fallback();
        const input = route.request().postDataJSON();
        expect(input).toMatchObject({ body: nestedText, rootCommentId: root.id, replyToCommentId: reply.id });
        await route.continue({ postData: { ...input, rootCommentId: secondRoot.id } });
      });
      try {
        const rejected = responseFor(readerPage, "POST", commentsApi);
        await readerPage.getByRole("button", { name: "发布回复", exact: true }).click();
        const response = await rejected;
        expect(response.status()).toBe(400);
        const error = await response.json();
        expect(error).toMatchObject({ code: "VALIDATION_ERROR", message: "回复对象必须属于同一帖子和评论串" });
        await expect(readerPage.getByRole("alert")).toContainText("回复对象必须属于同一帖子和评论串");
        await expect(nestedInput).toHaveValue(nestedText);
      } finally {
        await readerPage.unroute(commentsUrl);
      }
    });
    const nestedCreated = responseFor(readerPage, "POST", commentsApi);
    await readerPage.getByRole("button", { name: "发布回复", exact: true }).click();
    expect(await data<Comment>(await nestedCreated, 201)).toMatchObject({
      rootCommentId: root.id, replyToCommentId: reply.id,
    });
    await expect(readerPage.getByText(nestedText, { exact: true })).toBeVisible();
    await expect(readerPage.getByRole("alert")).toHaveCount(0);
    await capture(readerPage, "community-comments", readerPage.getByRole("region", { name: "评论", exact: true }));
    readerPage.once("dialog", (dialog) => dialog.accept());
    await edited.getByRole("button", { name: "删除评论", exact: true }).click();
    const deleted = readerPage.locator(".community-comment").filter({ hasText: "评论已删除" });
    await expect(deleted).toBeVisible();
    await expect(deleted.getByRole("link")).toHaveCount(0);
    await expect(deleted.getByRole("button", { name: "回复", exact: true })).toHaveCount(0);
    await expect(edited).toHaveCount(0);
    await expect(readerPage.getByText(nestedText, { exact: true })).toBeVisible();
    const roots = await data<ResultPage<Comment>>(await visitor.context.request.get(commentsApi, { headers: visitor.headers }));
    expect(roots.items[0]).toMatchObject({ id: root.id, author: null, body: null, displayState: "DELETED" });

    // Each fixture reader writes fewer than ten comments in one rate-limit window.
    for (let group = 0; group < 5; group++) {
      const account = await register(context, `paging${group}_${suffix}`, `验收分页读者 ${group + 1}`);
      const headers = await login(context, account.credentials);
      for (let index = group * 8; index < group * 8 + 8; index++) {
        if (index === 39) break;
        const input = index < 20 ? { body: `根评论分页验收 ${index + 1}` }
          : { body: `回复分页验收 ${index - 19}`, rootCommentId: root.id, replyToCommentId: reply.id };
        await data<Comment>(await context.request.post(commentsApi, { headers: command(headers), data: input }), 201);
      }
    }
    await readerPage.reload();
    const rootsNav = readerPage.getByRole("navigation", { name: "根评论分页" });
    const repliesNav = readerPage.getByRole("navigation", { name: "回复分页" });
    await expect(rootsNav).toContainText("第 1 页，共 22 项");
    await repliesButton.click();
    await expect(repliesNav).toContainText("第 1 页，共 21 项");
    await repliesNav.getByRole("button", { name: "下一页" }).click();
    await expect(repliesNav).toContainText("第 2 页，共 21 项");
    await expect(readerPage.getByText("回复分页验收 19", { exact: true })).toBeVisible();
    await expect(rootsNav).toContainText("第 1 页，共 22 项");
    await rootsNav.getByRole("button", { name: "下一页" }).click();
    await expect(rootsNav).toContainText("第 2 页，共 22 项");
    await expect(readerPage.getByText("根评论分页验收 20", { exact: true })).toBeVisible();
    await expect(repliesNav).toHaveCount(0);
  });

  await test.step("owner compares revisions, read-only transition preserves public reading", async () => {
    await authorPage.goto(`/account/posts/${postId}`);
    await authorPage.locator("#discussion-body").fill(source + "\n\n验收修订段落。");
    await authorPage.getByLabel("修改说明（选填）").fill("补充验收记录。");
    await authorPage.getByRole("button", { name: "发布修订", exact: true }).click();
    await expect(authorPage.getByText("讨论修订已发布。")).toBeVisible();
    await authorPage.getByRole("button", { name: "查看历史" }).click();
    const version = (number: number) => authorPage.getByRole("listitem").filter({ hasText: `第 ${number} 版 ${title}` });
    await version(1).getByRole("button", { name: "选为原版本" }).click();
    await version(2).getByRole("button", { name: "选为对照版本" }).click();
    await authorPage.getByRole("button", { name: "比较源文本" }).click();
    await expect(authorPage.locator(".community-diff mark")).toHaveText("验收修订段落。");
    const own = await data<OwnPost>(await author.context.request.get(`/api/v1/me/posts/${postId}`, { headers: author.headers }));
    expect((await visitor.context.request.get(`/api/v1/me/posts/${postId}`, { headers: visitor.headers })).status()).toBe(404);
    const adminPage = admin.page;
    const childButton = adminPage.getByRole("button", { name: child.name, exact: true });
    const deactivate = async () => {
      await adminPage.getByLabel("启用分区").uncheck();
      await adminPage.getByRole("button", { name: "保存配置" }).click();
    };
    await childButton.click();
    await deactivate();
    await expect(adminPage.getByRole("alert")).toContainText("版本已更新，请刷新后重试");
    const refreshed = responseFor(adminPage, "GET", "/api/v1/community/sections");
    await adminPage.getByRole("button", { name: "刷新分区" }).click();
    expect((await refreshed).status()).toBe(200);
    await expect(adminPage.getByText("正在读取分区…")).toHaveCount(0);
    await childButton.click();
    await expect(adminPage.getByLabel("分区规则", { exact: true })).toHaveValue("验收规则：写清合成示例的出处。");
    await deactivate();
    await expect(adminPage.getByText("分区配置已更新。")).toBeVisible();
    await authorPage.reload();
    await expect(authorPage.getByText("当前讨论只读。")).toBeVisible();
    await expect(authorPage.locator("#discussion-body")).toHaveAttribute("readonly", "");
    const restricted = await author.context.request.post(`/api/v1/posts/${postId}/revisions`, {
      headers: command(author.headers), data: { title, body: "验收只读拒写", baseRevisionId: own.latestRevision.revisionId, lockVersion: own.lockVersion },
    });
    expect(restricted.status()).toBe(409);
    const restrictedCreate = await author.context.request.post("/api/v1/posts", {
      headers: command(author.headers), data: { title: "验收只读拒写", body: "仅用于验收", sectionId: child.id, sourceFormat: "MARKDOWN" },
    });
    expect(restrictedCreate.status()).toBe(409);
    await readerPage.goto(sectionPath);
    await expect(readerPage.getByText("只读分区", { exact: true })).toBeVisible();
    await expect(readerPage.getByRole("link", { name: "在本区发布讨论" })).toHaveCount(0);
    await readerPage.getByRole("link", { name: title, exact: true }).click();
    await expect(readerPage.getByRole("heading", { name: title, exact: true })).toBeVisible();
    await expect(readerPage.getByText("验收修订段落。", { exact: true })).toBeVisible();
    await expect(readerPage.getByLabel("写评论")).toHaveCount(0);
    await readerPage.goto("/account");
    await expect(readerPage.getByRole("link", { name: title, exact: true })).toBeVisible();
    await readerPage.getByRole("tab", { name: "浏览历史", exact: true }).click();
    await expect(readerPage.locator(".content-entry")).toHaveCount(1);
    await expect(readerPage.getByRole("link", { name: title, exact: true })).toBeVisible();
  });
});

const checksum = (body: string) => createHash("sha256").update(body).digest("hex");

async function enterLongArticle(page: Page, field: Locator, body: string) {
  await field.fill("");
  await expect(field).toBeFocused();
  // Bound each insert command so Edge can process the full multi-line input.
  const chunkSize = 4096;
  for (let offset = 0; offset < body.length; offset += chunkSize) {
    await page.keyboard.insertText(body.slice(offset, offset + chunkSize));
  }
}

async function addReference(page: Page, title: string, number: number, location: string) {
  await page.getByRole("button", { name: "添加参考文献" }).click();
  const fields = page.getByRole("group", { name: `文献 ${number}`, exact: true });
  const titleInput = fields.getByLabel("标题", { exact: true });
  await expect(titleInput).toBeFocused();
  await titleInput.fill(title);
  await fields.getByLabel("作者", { exact: true }).fill("验收示例作者");
  await fields.getByLabel("页码或位置").fill(location);
  return (await titleInput.getAttribute("id"))!.replace("reference-title-", "");
}

test("oversized article keeps unsaved source through quota approval, then publishes reviewed snapshots", async ({ context, openActor }) => {
  test.setTimeout(180000);
  const suffix = Date.now();
  const writer = await register(context, `articles_${suffix}`, "验收文章贡献者");
  const reviewer = await register(context, `revision_${suffix}`, "验收社区审核者");
  const reader = await register(context, `readpost_${suffix}`, "验收文章读者");
  const grantPlaceholder = await register(context, `admingrant_${suffix}`, "验收系统管理员");
  execFileSync("python", ["e2e/grant-stage-two-roles.py", writer.id, reviewer.id, grantPlaceholder.id],
    { windowsHide: true, stdio: "pipe" });
  const author = await openActor(writer.credentials);
  const moderator = await openActor(reviewer.credentials);
  const visitor = await openActor(reader.credentials);
  const admin = await openActor(adminCredentials());
  const externalRequests: string[] = [];
  for (const actor of [author, moderator, visitor]) {
    actor.context.on("request", (request) => {
      if (new URL(request.url()).hostname === "example.test") externalRequests.push(request.url());
    });
  }
  const page = author.page;
  const title = `文章版本验收 ${suffix}`;
  const prefix = [
    "## 验收初稿", "", "**核对阅读记录**，详见合成文献 [1]。",
    "", "编号示例 `[1]` 和 [1](https://example.test/reading) 属于代码与普通链接。",
    "", "| 记录项 | 内容 |", "| --- | --- |", "| 版本 | 隔离环境验收 |", "",
  ].join("\n");
  const paragraph = "仅用于浏览器验收的合成学习示例。核对书名、版本与页码，再保留原始记录。\n\n";
  const body = prefix + paragraph.repeat(Math.ceil((100001 - prefix.length) / paragraph.length)).slice(0, 100001 - prefix.length);
  const bodyField = page.locator("#article-body");
  const approvalNote = "仅为合成稿验收批准单篇正文额度。";
  const requestReason = "验收合成长文超过默认正文额度一个字符。";
  await admin.page.goto("/admin/community/sections");
  const section = await createSection(admin.page, `验收科普文章 ${suffix}`, `e2e-article-${suffix}`);

  const { id, articleApi } = await test.step("private identity stores only the title while keeping all 100001 characters", async () => {
    await page.goto("/account/articles/new");
    await page.locator("#article-title").fill(title);
    await enterLongArticle(page, bodyField, body);
    await expect(page.locator("#article-count")).toContainText("100001 / 100000 字符");
    await expect(page.getByRole("button", { name: "保存草稿", exact: true })).toBeDisabled();
    await expect(bodyField).toHaveAttribute("aria-invalid", "true");
    const creation = responseFor(page, "POST", "/api/v1/me/articles/draft-identities");
    await page.locator("[data-create-draft-identity]").click();
    const identity = await data<Article>(await creation, 201);
    expect(identity.latestRevision).toMatchObject({ title, body: "", references: [], state: "DRAFT" });
    expect(identity).toMatchObject({ visibilityStatus: "DRAFT", publishedRevisionId: null });
    expect((await visitor.context.request.get(`/api/v1/posts/${identity.id}`, { headers: visitor.headers })).status()).toBe(404);
    await expect(page).toHaveURL(`/account/articles/${identity.id}`);
    await expect(page.getByText("已创建私有草稿身份。正文和文献仍待保存。")).toBeVisible();
    await expect(page.getByText("当前修改尚未保存。")).toBeVisible();
    await expect(page.getByText("草稿已保存", { exact: true })).toHaveCount(0);
    expect(checksum(await bodyField.inputValue())).toBe(checksum(body));
    await page.evaluate(() => { document.documentElement.dataset.e2eQuotaRefresh = "same-document"; });
    return { id: identity.id, articleApi: `/api/v1/me/articles/${identity.id}` };
  });

  const publicApi = `/api/v1/posts/${id}`;
  const publicPath = `/community/posts/${id}`;
  const saveButton = page.getByRole("button", { name: "保存草稿", exact: true });
  await test.step("administrator approves quota and author refreshes it without reloading or publishing", async () => {
    await page.getByLabel("申请额度", { exact: true }).fill("110000");
    await page.getByLabel("扩容原因", { exact: true }).fill(requestReason);
    await page.getByRole("button", { name: "提交扩容申请" }).click();
    await expect(page.getByText("最近申请待审批，申请 110000 字符")).toBeVisible();
    const before = await data<Article>(await author.context.request.get(articleApi, { headers: author.headers }));
    expect(before.latestRevision.body).toBe("");
    const quotaId = before.quota.latestRequest?.id;
    expect(quotaId).toBeTruthy();
    const ownDecision = await author.context.request.post(`/api/v1/admin/community/quota-requests/${quotaId}/decisions`, {
      headers: command(author.headers), data: { lockVersion: 0, decision: "APPROVED", approvedLimit: 110000, note: approvalNote },
    });
    expect(ownDecision.status()).toBe(403);
    await admin.page.goto("/admin/community/quotas");
    const row = admin.page.getByRole("listitem").filter({ has: admin.page.getByRole("heading", { name: `文章 ${id} · 待审批`, exact: true }) });
    await expect(row).toContainText(requestReason);
    await row.getByRole("button", { name: "处理申请" }).click();
    await admin.page.getByLabel("批准额度（字符）").fill("110000");
    await admin.page.getByLabel("审批意见", { exact: true }).fill(approvalNote);
    await capture(admin.page, "quota-admin", admin.page.getByRole("group", { name: "审批决定" }));
    await admin.page.getByRole("button", { name: "确认审批" }).click();
    await expect(admin.page.getByRole("status").filter({ hasText: "额度申请已批准。" })).toBeVisible();
    await expect(row).toHaveCount(0);
    await admin.page.getByLabel("申请状态").selectOption("APPROVED");
    const approvedRow = admin.page.getByRole("listitem").filter({ has: admin.page.getByRole("heading", { name: `文章 ${id} · 已批准`, exact: true }) });
    await expect(approvedRow).toContainText(approvalNote);
    expect((await visitor.context.request.get(publicApi, { headers: visitor.headers })).status()).toBe(404);
    await page.locator("[data-refresh-quota]").click();
    await expect(page.locator("#article-count")).toHaveText("100001 / 110000 字符");
    await expect(page.getByText("当前修改尚未保存。")).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.dataset.e2eQuotaRefresh)).toBe("same-document");
    expect(checksum(await bodyField.inputValue())).toBe(checksum(body));
    await capture(page, "article-draft", undefined, page.locator("#article-title"));
    await page.getByRole("button", { name: "申请历史" }).click();
    await expect(page.getByRole("region", { name: "申请记录" })).toContainText(approvalNote);
    await capture(page, "article-quota", page.getByRole("region", { name: "正文额度与扩容" }));
    await saveButton.click();
    await expect(page.getByText("草稿已保存", { exact: true })).toBeVisible();
    const saved = await data<Article>(await author.context.request.get(articleApi, { headers: author.headers }));
    expect(saved.latestRevision.bodyCharCount).toBe(100001);
    expect(checksum(saved.latestRevision.body)).toBe(checksum(body));
  });

  const firstReference = "验收文献：版本核对示例";
  const secondReference = "验收文献：页码登记示例";
  const sourceUrl = "https://example.test/citations/page";
  const sourceDoi = "10.1234/e2e.synthetic";
  const reorderedBody = body.replace("合成文献 [1]", "合成文献 [2]");
  const { firstKey, secondKey } = await test.step("references reorder by stable key while code and links stay intact", async () => {
    await page.locator("#article-section").selectOption(section.id);
    const firstKey = await addReference(page, firstReference, 1, "第 12 页");
    const secondKey = await addReference(page, secondReference, 2, "第 25 页");
    const fields = page.getByRole("group", { name: "文献 2", exact: true });
    await fields.getByLabel("DOI", { exact: true }).fill(sourceDoi);
    await fields.getByLabel("网页地址", { exact: true }).fill(sourceUrl);
    await capture(page, "article-references", page.getByRole("region", { name: "编辑参考文献" }), fields);
    await saveButton.click();
    await expect(page.getByText("草稿已保存", { exact: true })).toBeVisible();
    const saved = await data<Article>(await author.context.request.get(articleApi, { headers: author.headers }));
    expect(saved.latestRevision.references.map(({ referenceKey }) => referenceKey)).toEqual([firstKey, secondKey]);
    await page.getByText("重排已保存的文献", { exact: true }).click();
    await page.getByRole("button", { name: `上移 ${secondReference}`, exact: true }).click();
    const reorder = responseFor(page, "POST", `${articleApi}/references/reorder`);
    await page.getByRole("button", { name: "确认重排与更新引用" }).click();
    const result = await data<Article>(await reorder);
    expect(result.lockVersion).toBeGreaterThan(saved.lockVersion);
    expect(result.latestRevision.references.map(({ referenceKey, displayNumber }) => [referenceKey, displayNumber]))
      .toEqual([[secondKey, 1], [firstKey, 2]]);
    expect(checksum(result.latestRevision.body)).toBe(checksum(reorderedBody));
    await expect(page.getByText("文献编号及正文引用已更新。")).toBeVisible();
    expect(checksum(await bodyField.inputValue())).toBe(checksum(reorderedBody));
    return { firstKey, secondKey };
  });

  const submit = async () => {
    await page.getByRole("button", { name: "提交审核", exact: true }).click();
    await expect(page.getByText("已提交审核，审核通过后公开展示。")).toBeVisible();
    await expect(bodyField).toHaveAttribute("readonly", "");
  };
  const openReview = async () => {
    await moderator.page.goto("/reviews");
    await moderator.page.getByRole("link", { name: title, exact: true }).click();
    await expect(moderator.page.getByRole("heading", { name: "待审核文章" })).toBeVisible();
    return new URL(moderator.page.url()).pathname;
  };
  const approve = async () => {
    await moderator.page.getByRole("button", { name: "领取审核任务" }).click();
    await moderator.page.getByLabel("审核意见（必填）").fill("核对合成稿的来源占位、正文与版本，仅用于验收。");
    await moderator.page.getByRole("button", { name: "确认审核结论" }).click();
    await expect(moderator.page.getByRole("status").filter({ hasText: "审核已通过" })).toBeVisible();
  };
  const assertRendered = async (target: Page, heading: string, referenceTitle: string) => {
    await expect(target.locator(".community-prose").getByRole("heading", { name: heading, exact: true })).toBeVisible();
    await expect(target.locator(".community-prose").getByRole("link", { name: "[2]", exact: true })).toHaveAttribute("href", "#reference-" + firstKey);
    await expect(target.locator(".community-prose code")).toHaveText("[1]");
    await expect(target.locator(".community-prose").getByRole("link", { name: "1", exact: true })).toHaveAttribute("href", "https://example.test/reading");
    const references = target.getByRole("region", { name: "参考文献", exact: true }).getByRole("listitem");
    await expect(references.nth(0)).toContainText(secondReference);
    await expect(references.nth(0)).toContainText(`DOI ${sourceDoi}`);
    await expect(references.nth(0).getByRole("link", { name: "打开文献链接" })).toHaveAttribute("href", sourceUrl);
    await expect(references.nth(1)).toContainText(referenceTitle);
  };
  const firstPublic = await test.step("only ordinary review publishes the frozen first snapshot", async () => {
    await submit();
    const reviewPath = await openReview();
    const frozen = await data<{ article: { body: string; references: Reference[] } }>(
      await moderator.context.request.get("/api/v1" + reviewPath, { headers: moderator.headers }),
    );
    expect(checksum(frozen.article.body)).toBe(checksum(reorderedBody));
    expect(frozen.article.references.map((row) => row.referenceKey)).toEqual([secondKey, firstKey]);
    await assertRendered(moderator.page, "验收初稿", firstReference);
    await admin.page.goto(reviewPath);
    await expect(admin.page.getByRole("heading", { name: "暂时无法访问" })).toBeVisible();
    expect((await admin.context.request.get("/api/v1" + reviewPath, { headers: admin.headers })).status()).toBe(403);
    expect((await visitor.context.request.get(publicApi, { headers: visitor.headers })).status()).toBe(404);
    await approve();
    await visitor.page.goto(publicPath);
    await assertRendered(visitor.page, "验收初稿", firstReference);
    const published = await data<Post>(await visitor.context.request.get(publicApi, { headers: visitor.headers }));
    expect(published).toMatchObject({ releaseMethod: "MANUAL_REVIEW", section: { id: section.id } });
    expect(checksum(published.body!)).toBe(checksum(reorderedBody));
    return published;
  });

  await test.step("private revisions leave the first version visible until separately approved", async () => {
    await page.reload();
    await page.getByRole("button", { name: "创建修订", exact: true }).click();
    await expect(page.getByText("已创建修订草稿。")).toBeVisible();
    const revisedBody = reorderedBody.replace("验收初稿", "验收修订稿");
    await bodyField.focus();
    await page.keyboard.press("Control+Home");
    await page.keyboard.press("Shift+End");
    await page.keyboard.insertText("## 验收修订稿");
    expect(checksum(await bodyField.inputValue())).toBe(checksum(revisedBody));
    await page.getByRole("button", { name: `[2] ${firstReference}`, exact: true }).click();
    const revisedRef = firstReference + "（修订）";
    await page.getByRole("group", { name: "文献 2", exact: true }).getByLabel("标题", { exact: true }).fill(revisedRef);
    await saveButton.click();
    await expect(page.getByText("草稿已保存", { exact: true })).toBeVisible();
    await visitor.page.reload();
    await assertRendered(visitor.page, "验收初稿", firstReference);
    await expect(visitor.page.locator(".community-prose").getByRole("heading", { name: "验收修订稿" })).toHaveCount(0);
    expect((await data<Post>(await visitor.context.request.get(publicApi, { headers: visitor.headers }))).revisionId).toBe(firstPublic.revisionId);
    await submit();
    await openReview();
    await assertRendered(moderator.page, "验收修订稿", revisedRef);
    await approve();
    await visitor.page.reload();
    await assertRendered(visitor.page, "验收修订稿", revisedRef);
    const secondPublic = await data<Post>(await visitor.context.request.get(publicApi, { headers: visitor.headers }));
    expect(secondPublic.revisionId).not.toBe(firstPublic.revisionId);
    expect(secondPublic.publishedAt).toBe(firstPublic.publishedAt);
    expect(checksum(secondPublic.body!)).toBe(checksum(revisedBody));
    expect(secondPublic.references.map(({ referenceKey, title: refTitle }) => [referenceKey, refTitle]))
      .toEqual([[secondKey, secondReference], [firstKey, revisedRef]]);
  });
  expect(externalRequests).toEqual([]);
});

test("text policy administration creates versioned rules and detects concurrent activation", async ({ openActor }) => {
  test.setTimeout(120000);
  const admin = await openActor(adminCredentials());
  const { page, context, headers } = admin;
  const versionsApi = "/api/v1/admin/community/text-policies";
  const headApi = "/api/v1/admin/community/text-policy-head";
  const getPolicy = async () => data<TextPolicy>(await context.request.get("/api/v1/community/text-policy", { headers }));
  const previous = await getPolicy();
  const reason = `验收政策版本 ${Date.now()}`;
  try {
    await page.goto("/admin/community/policies");
    await page.getByRole("button", { name: "从当前政策新建版本" }).click();
    await page.getByLabel("评论字符", { exact: true }).fill(String(previous.config.maxCommentChars - 1));
    await page.getByLabel("变更原因").fill(reason);
    const creation = responseFor(page, "POST", versionsApi);
    await page.getByRole("button", { name: "保存为新版本" }).click();
    const target = await data<PolicyVersion>(await creation, 201);
    expect(target.config.maxArticleChars).toBe(previous.config.maxArticleChars);
    expect((await getPolicy()).policyId).toBe(previous.policyId);
    const row = page.getByRole("listitem").filter({ hasText: reason });
    await row.getByRole("button", { name: "启用此版" }).click();
    await page.getByLabel("启用原因").fill("验收使用较低的评论字符数。");

    const competing = await data<PolicyVersion>(await context.request.post(versionsApi, {
      headers: command(headers), data: { config: previous.config, reason: "验收并发政策操作。" },
    }), 201);
    await data<PolicyHead>(await context.request.put(headApi, {
      headers: command(headers), data: { lockVersion: previous.lockVersion, policyId: competing.policyId, reason: "验收并发启用。" },
    }));
    const conflict = responseFor(page, "PUT", headApi);
    await page.getByRole("button", { name: "确认启用" }).click();
    expect((await conflict).status()).toBe(409);
    await expect(page.getByRole("alert")).toContainText("文本政策已更新");
    await expect(page.getByLabel("启用原因")).toHaveValue("验收使用较低的评论字符数。");
    expect((await getPolicy()).policyId).toBe(competing.policyId);
    await page.getByRole("button", { name: "刷新政策" }).click();
    page.once("dialog", (dialog) => dialog.accept());
    await page.getByRole("button", { name: "重新核对当前政策" }).click();
    await page.getByRole("button", { name: "确认启用" }).click();
    await expect(row.getByRole("heading")).toHaveText(`第 ${target.versionNo} 版 · 使用中`);
    const active = await getPolicy();
    expect(active).toMatchObject({
      policyId: target.policyId,
      lockVersion: previous.lockVersion + 2,
      config: { maxCommentChars: previous.config.maxCommentChars - 1 },
    });
  } finally {
    const current = await getPolicy();
    if (current.policyId !== previous.policyId) {
      await data<PolicyHead>(await context.request.put(headApi, {
        headers: command(headers),
        data: { lockVersion: current.lockVersion, policyId: previous.policyId, reason: "浏览器验收结束，恢复原活动版本。" },
      }));
    }
  }
  expect(await getPolicy()).toMatchObject({ policyId: previous.policyId, config: previous.config });
});
