import { test, expect } from "@playwright/test";
import { mkdirSync, readFileSync } from "node:fs";

test("registration form enforces ASCII password complexity and length boundaries", async ({
  page,
}) => {
  await page.goto("/register");
  const password = page.getByLabel("密码", { exact: true });
  await expect(password).toHaveAttribute("minlength", "10");
  await expect(password).toHaveAttribute("maxlength", "12");
  await expect(page.locator("#password-hint")).toContainText(
    "大写字母、小写字母",
  );
  await page.getByLabel("用户名", { exact: true }).fill(`policy_${Date.now()}`);
  await page.getByLabel("昵称", { exact: true }).fill("密码规则验证");
  const requests: string[] = [];
  page.on("request", (request) => {
    if (request.method() === "POST" && request.url().endsWith("/auth/register"))
      requests.push(request.url());
  });
  for (const invalid of [
    "Abcdefg12",
    "abcdefgh12",
    "ABCDEFGH12",
    "Abcdefghij",
    "Abcdef12中文",
    "Abcdef12 !",
    "Abcdefgh１!",
  ]) {
    await password.fill(invalid);
    expect(
      await password.evaluate((element: HTMLInputElement) =>
        element.checkValidity(),
      ),
    ).toBe(false);
    await page.getByRole("button", { name: "创建账号", exact: true }).click();
    expect(requests).toHaveLength(0);
  }
  for (const valid of [
    "Abcdefgh12",
    "Abcdefghi!",
    "Abcdefghij1!",
    "Abcdefg'12",
    "Abcdefg\\12",
  ]) {
    await password.fill(valid);
    expect(
      await password.evaluate((element: HTMLInputElement) =>
        element.checkValidity(),
      ),
    ).toBe(true);
  }
});

test("two tabs restore the shared session concurrently and logout propagates", async ({
  context,
}) => {
  const username = `tabs_${Date.now()}`;
  const password = "TabsPass12!";
  expect(
    (
      await context.request.post("/api/v1/auth/register", {
        data: { username, password, nickname: "多页学习者" },
      })
    ).status(),
  ).toBe(201);
  expect(
    (
      await context.request.post("/api/v1/auth/login", {
        data: { username, password },
      })
    ).status(),
  ).toBe(200);
  const first = await context.newPage(),
    second = await context.newPage();
  await Promise.all([first.goto("/account"), second.goto("/account")]);
  for (const page of [first, second]) {
    await expect(page).toHaveURL(/\/account$/);
    await expect(page.getByRole("heading", { level: 1 })).toHaveText(
      "多页学习者",
    );
  }
  await first.getByRole("button", { name: "退出登录", exact: true }).click();
  await expect(first).toHaveURL(/\/login/);
  await expect(second).toHaveURL(/\/login/);
});

test("learner registration, profile, contribution application, cookie restoration and logout", async ({
  page,
  context,
}) => {
  const name = `browser_${Date.now()}`;
  const password = "WebPass123!";
  const errors: string[] = [];
  page.on("pageerror", (e) => errors.push(e.message));
  await page.goto("/register");
  await page.getByLabel("用户名", { exact: true }).fill(name);
  await page.getByLabel("昵称", { exact: true }).fill("浏览器验收用户");
  await page.getByLabel("密码", { exact: true }).fill(password);
  await page.getByRole("button", { name: "创建账号", exact: true }).click();
  await expect(page).toHaveURL(/\/login/);
  await page.getByLabel("用户名", { exact: true }).fill(name);
  await page.getByLabel("密码", { exact: true }).fill(password);
  await page.getByRole("button", { name: "登录", exact: true }).click();
  await expect(page).toHaveURL(/\/account$/);
  await expect(page.getByText("学习者", { exact: true })).toBeVisible();
  await page
    .getByRole("link", { name: "编辑资料", exact: true })
    .first()
    .click();
  await page.getByLabel("昵称", { exact: true }).fill("持续学习者");
  await page
    .getByLabel("个人简介", { exact: true })
    .fill("关注方剂学习与可信来源。");
  await page.getByRole("button", { name: "保存资料" }).click();
  await expect(
    page.getByRole("status").filter({ hasText: "个人资料已保存" }),
  ).toBeVisible();
  await page.reload();
  await expect(page.getByLabel("昵称", { exact: true })).toHaveValue(
    "持续学习者",
  );
  expect(
    await page.evaluate(() => ({
      local: Object.keys(localStorage),
      session: Object.keys(sessionStorage),
    })),
  ).toEqual({ local: [], session: [] });
  const cookies = await context.cookies();
  expect(cookies.find((c) => c.name === "tcm_refresh")?.httpOnly).toBe(true);
  await page.getByRole("link", { name: "贡献资格", exact: true }).click();
  await page
    .getByLabel("申请说明", { exact: true })
    .fill("希望贡献有来源可查的中医药学习资料。");
  await page.getByRole("button", { name: "提交申请", exact: true }).click();
  await expect(page.getByText("待处理", { exact: true })).toBeVisible();
  await expect(
    page.getByRole("button", { name: "提交申请", exact: true }),
  ).toHaveCount(0);
  mkdirSync("../runtime/screenshots", { recursive: true });
  await page.screenshot({
    path: "../runtime/screenshots/learner-applications.png",
    fullPage: true,
  });
  await page.goto("/admin/users");
  await expect(page).toHaveURL(/\/forbidden$/);
  await page.goto("/account");
  await page.getByRole("button", { name: "退出登录", exact: true }).click();
  await expect(page).toHaveURL(/\/login/);
  await page.goto("/account");
  await expect(page).toHaveURL(/\/login/);
  expect(errors).toEqual([]);
});

test("public pages fit mobile and desktop without horizontal overflow", async ({
  page,
}) => {
  mkdirSync("../runtime/screenshots", { recursive: true });
  for (const viewport of [
    { width: 1440, height: 1000 },
    { width: 390, height: 844 },
  ]) {
    await page.setViewportSize(viewport);
    for (const path of ["/", "/login", "/register"]) {
      await page.goto(path);
      await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
      expect(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= window.innerWidth,
        ),
      ).toBe(true);
      expect(await page.locator("form form").count()).toBe(0);
    }
    await page.goto("/");
    await page.screenshot({
      path: `../runtime/screenshots/home-${viewport.width}.png`,
      fullPage: true,
    });
  }
});

test("administrator approves contribution and separately grants reviewer access", async ({
  page,
  request,
}) => {
  const admin = JSON.parse(
    readFileSync("../runtime/e2e-admin.json", "utf8").replace(/^\uFEFF/, ""),
  );
  const username = `applicant_${Date.now()}`;
  const password = "ApplyPass12!";
  expect(
    (
      await request.post("/api/v1/auth/register", {
        data: { username, password, nickname: "待审批贡献者" },
      })
    ).status(),
  ).toBe(201);
  const login = await request.post("/api/v1/auth/login", {
    data: { username, password },
  });
  const learner = (await login.json()).data;
  expect(
    (
      await request.post("/api/v1/me/role-applications", {
        headers: { Authorization: `Bearer ${learner.accessToken}` },
        data: {
          roleCode: "CONTRIBUTOR",
          statement: "提供可核验来源的科普资料。",
        },
      })
    ).status(),
  ).toBe(201);
  await page.goto("/login");
  await page.getByLabel("用户名", { exact: true }).fill(admin.username);
  await page.getByLabel("密码", { exact: true }).fill(admin.password);
  await page.getByRole("button", { name: "登录", exact: true }).click();
  await expect(page).toHaveURL(/\/account$/);
  await page.getByRole("link", { name: "管理后台", exact: true }).click();
  const row = page.getByRole("row").filter({ hasText: username });
  await row.getByRole("button", { name: "处理申请" }).click();
  await page
    .getByLabel("处理意见（必填）", { exact: true })
    .fill("资料背景符合要求。");
  await page.getByRole("button", { name: "通过申请", exact: true }).click();
  await expect(row).toContainText("已通过");
  await page.getByRole("link", { name: "用户管理", exact: true }).click();
  const userRow = page.getByRole("row").filter({ hasText: username });
  await expect(userRow).toContainText("贡献者");
  await userRow.getByRole("button", { name: "角色设置" }).click();
  await page.getByRole("dialog").getByText("审核者", { exact: true }).click();
  await expect(
    page.getByRole("checkbox", { name: "审核者", exact: true }),
  ).toBeChecked();
  await page.getByRole("button", { name: "保存角色", exact: true }).click();
  await expect(userRow).toContainText("审核者");
  await page.screenshot({
    path: "../runtime/screenshots/admin-users.png",
    fullPage: true,
  });
  await userRow.getByRole("button", { name: "禁用账号", exact: true }).click();
  await page.getByRole("button", { name: "确认", exact: true }).click();
  await expect(userRow).toContainText("已禁用");
  const forbidden = await request.get("/api/v1/me", {
    headers: { Authorization: `Bearer ${learner.accessToken}` },
  });
  expect(forbidden.status()).toBe(401);
});
