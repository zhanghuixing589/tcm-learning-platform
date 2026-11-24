import { test, expect } from "@playwright/test";
import { execFileSync } from "node:child_process";
import { mkdirSync } from "node:fs";

test("personal center presents identity and real empty collections before settings", async ({
  page,
  context,
}) => {
  const username = `profile_${Date.now()}`;
  const credentials = {
    username,
    password: "Profile123!",
    nickname: "本草学习者",
  };
  const longNickname = "本草学习者".repeat(16);
  expect(
    (
      await context.request.post("/api/v1/auth/register", { data: credentials })
    ).status(),
  ).toBe(201);
  expect(
    (
      await context.request.post("/api/v1/auth/login", {
        data: { username, password: credentials.password },
      })
    ).status(),
  ).toBe(200);
  await page.goto("/account");
  await expect(
    page.getByRole("heading", { name: "本草学习者", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("tab", { name: "我的收藏", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByText("还没有收藏的内容", { exact: true }),
  ).toBeVisible();
  await expect(page.getByLabel("昵称", { exact: true })).toHaveCount(0);
  await page
    .getByRole("link", { name: "编辑资料", exact: true })
    .first()
    .click();
  await expect(page).toHaveURL(/\/account\/settings$/);
  await page.getByLabel("昵称", { exact: true }).fill(longNickname);
  await page.getByRole("button", { name: "保存资料", exact: true }).click();
  await expect(
    page.getByRole("status").filter({ hasText: "个人资料已保存" }),
  ).toBeVisible();
  const avatar = await page.evaluate(() => {
    const canvas = document.createElement("canvas");
    canvas.width = 48;
    canvas.height = 48;
    const context = canvas.getContext("2d")!;
    context.fillStyle = "#166534";
    context.fillRect(0, 0, 48, 48);
    return canvas.toDataURL("image/png").split(",")[1];
  });
  await page
    .locator('input[type="file"]')
    .setInputFiles({
      name: "avatar.png",
      mimeType: "image/png",
      buffer: Buffer.from(avatar, "base64"),
    });
  await expect(
    page.getByRole("status").filter({ hasText: "头像已更新" }),
  ).toBeVisible();
  await expect(page.locator(".user-avatar img")).toBeVisible();
  await page.getByRole("link", { name: "返回个人中心", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: longNickname, exact: true }),
  ).toBeVisible();
  await page.getByRole("tab", { name: "浏览历史", exact: true }).click();
  await expect(page).toHaveURL(/section=history/);
  await expect(page.getByText("还没有浏览记录", { exact: true })).toBeVisible();
  await page.reload();
  await expect(
    page.getByRole("tab", { name: "浏览历史", exact: true }),
  ).toHaveAttribute("aria-selected", "true");
  for (const width of [375, 390, 720, 768, 1024, 1440]) {
    await page.setViewportSize({ width, height: 950 });
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBe(true);
  }
});

test("real collection pagination, resource interactions and article revisions work together", async ({
  page,
  context,
}) => {
  const suffix = Date.now();
  const register = async (username: string, nickname: string) => {
    const response = await context.request.post("/api/v1/auth/register", {
      data: { username, password: "Profile123!", nickname },
    });
    expect(response.status()).toBe(201);
    return (await response.json()).data.id as string;
  };
  const owner = await register(`writer_${suffix}`, "知行本草");
  const reader = await register(`library_${suffix}`, "林间读本草");
  execFileSync("python", ["e2e/seed-personal-center.py", owner, reader], {
    windowsHide: true,
    stdio: "pipe",
  });
  expect(
    (
      await context.request.post("/api/v1/auth/login", {
        data: { username: `library_${suffix}`, password: "Profile123!" },
      })
    ).status(),
  ).toBe(200);
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await page.goto("/account");
  await expect(page.getByText("共 24 项", { exact: true })).toBeVisible();
  await expect(
    page.getByRole("heading", {
      name: "如何核对一篇中医科普文章的来源",
      exact: true,
    }),
  ).toBeVisible();
  mkdirSync("../runtime/screenshots", { recursive: true });
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.screenshot({
    path: "../runtime/screenshots/personal-center-desktop.png",
    fullPage: false,
  });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({
    path: "../runtime/screenshots/personal-center-mobile.png",
    fullPage: false,
  });
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.getByLabel("第 2 页", { exact: true }).click();
  await expect(page).toHaveURL(/page=2/);
  await expect(page.locator(".content-entry")).toHaveCount(4);
  await page.goBack();
  await expect(page.locator(".content-entry")).toHaveCount(20);
  await page
    .getByRole("link", { name: "如何核对一篇中医科普文章的来源", exact: true })
    .click();
  await page.getByRole("button", { name: /^点赞/ }).click();
  await expect(page.getByRole("button", { name: /^已点赞/ })).toHaveAttribute(
    "aria-pressed",
    "true",
  );
  await page.getByRole("link", { name: "返回个人中心", exact: true }).click();
  await page
    .locator(".content-entry")
    .first()
    .getByRole("button", { name: "取消收藏", exact: true })
    .click();
  await expect(page.getByText("共 23 项", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "撤销", exact: true }).click();
  await expect(page.getByText("共 24 项", { exact: true })).toBeVisible();
  await page.getByRole("tab", { name: "浏览历史", exact: true }).click();
  await expect(page.locator(".content-entry")).toHaveCount(1);
  await page.getByRole("button", { name: "清空记录", exact: true }).click();
  await page
    .locator(".el-popconfirm")
    .getByRole("button", { name: "清空记录", exact: true })
    .click();
  await expect(page.getByText("还没有浏览记录", { exact: true })).toBeVisible();
  await page.getByRole("tab", { name: "我的发表", exact: true }).click();
  await page.getByRole("link", { name: "发表内容", exact: true }).click();
  await page.getByLabel("标题", { exact: true }).fill("我的第一篇学习分享");
  await page
    .getByLabel("正文", { exact: true })
    .fill("浏览器验收草稿，来源为本人的学习记录，不作为医学建议。");
  await page.getByRole("button", { name: "保存草稿", exact: true }).click();
  await expect(page.getByRole("status")).toHaveText("草稿已保存");
  await expect(page.getByText("提交审核前，请选择可投稿的分区并保存。")).toBeVisible();
  await page.locator("#article-section").selectOption({ label: `浏览器验收科普分区 ${reader}` });
  await page.getByRole("button", { name: "保存草稿", exact: true }).click();
  await expect(page.getByRole("status")).toHaveText("草稿已保存");
  await page.getByRole("button", { name: "提交审核", exact: true }).click();
  await expect(page.getByRole("status")).toContainText("已提交审核");
  await expect(page.getByLabel("正文", { exact: true })).toHaveAttribute(
    "readonly",
    "",
  );
  await page.getByRole("button", { name: "撤回审核", exact: true }).click();
  await page.getByRole("button", { name: "创建新草稿", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "保存草稿", exact: true }),
  ).toBeVisible();
  await expect(page.getByLabel("正文", { exact: true })).not.toHaveAttribute(
    "readonly",
    "",
  );
  expect(errors).toEqual([]);
});

test("unavailable statistics remain unknown and recover through retry", async ({
  page,
  context,
}) => {
  const username = `stats_${Date.now()}`;
  await context.request.post("/api/v1/auth/register", {
    data: { username, password: "Profile123!", nickname: "认真学习者" },
  });
  await context.request.post("/api/v1/auth/login", {
    data: { username, password: "Profile123!" },
  });
  await page.route("**/api/v1/me/profile-summary", (route) =>
    route.fulfill({
      status: 503,
      contentType: "application/json",
      body: JSON.stringify({
        code: "TEMPORARY_ERROR",
        message: "互动数据暂不可用",
        data: null,
      }),
    }),
  );
  await page.goto("/account");
  await expect(
    page.getByRole("button", { name: "未加载 获赞", exact: true }),
  ).toBeDisabled();
  await expect(
    page.getByRole("button", { name: "0 获赞", exact: true }),
  ).toHaveCount(0);
  await expect(
    page.getByText("还没有收藏的内容", { exact: true }),
  ).toBeVisible();
  await page.unroute("**/api/v1/me/profile-summary");
  await page.getByRole("button", { name: "重试", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "0 获赞", exact: true }),
  ).toBeEnabled();
});

test("public profile survives list failures and retries independently", async ({
  page,
  context,
}) => {
  const username = `retry_${Date.now()}`;
  const response = await context.request.post("/api/v1/auth/register", {
    data: { username, password: "Profile123!", nickname: "重试学习者" },
  });
  const user = (await response.json()).data;
  await context.request.post("/api/v1/auth/login", {
    data: { username, password: "Profile123!" },
  });
  await page.route(`**/api/v1/users/${user.id}/publications?*`, (route) =>
    route.fulfill({
      status: 503,
      contentType: "application/json",
      body: JSON.stringify({
        code: "TEMPORARY_ERROR",
        message: "请稍后再试",
        data: null,
      }),
    }),
  );
  await page.goto(`/users/${user.id}`);
  await expect(
    page.getByRole("heading", { name: "重试学习者", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("heading", { name: "发表内容暂未加载", exact: true }),
  ).toBeVisible();
  await page.unroute(`**/api/v1/users/${user.id}/publications?*`);
  await page.getByRole("button", { name: "重新加载列表", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "还没有公开发表的内容", exact: true }),
  ).toBeVisible();
});

test("following is reflected in the public profile and own following list", async ({
  page,
  context,
}) => {
  const suffix = Date.now();
  const register = async (username: string, nickname: string) => {
    const response = await context.request.post("/api/v1/auth/register", {
      data: { username, password: "Profile123!", nickname },
    });
    expect(response.status()).toBe(201);
    return (await response.json()).data.id as string;
  };
  const target = await register(`target_${suffix}`, "方剂分享者");
  await register(`reader_${suffix}`, "关注测试读者");
  expect(
    (
      await context.request.post("/api/v1/auth/login", {
        data: { username: `reader_${suffix}`, password: "Profile123!" },
      })
    ).status(),
  ).toBe(200);
  await page.goto(`/users/${target}`);
  await expect(
    page.getByRole("heading", { name: "方剂分享者", exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "关注", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "取消关注", exact: true }),
  ).toHaveText("已关注");
  await page.goto("/account?section=following");
  await expect(
    page.getByRole("link", { name: "方剂分享者", exact: false }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "1 关注", exact: true }),
  ).toBeVisible();
  await page
    .getByRole("button", { name: "取消关注 方剂分享者", exact: true })
    .click();
  await expect(page.getByText("还没有关注的人", { exact: true })).toBeVisible();
  await expect(
    page.getByRole("button", { name: "0 关注", exact: true }),
  ).toBeVisible();
});
