import { expect, test, type BrowserContext, type Page } from "@playwright/test";
import { execFileSync } from "node:child_process";
import { mkdirSync } from "node:fs";

test.use({ actionTimeout: 15000, navigationTimeout: 30000 });

test("real source upload, parsing, two reviews and public knowledge preserve evidence and versions", async ({
  browser,
  context,
  page,
}) => {
  test.setTimeout(180000);
  const suffix = Date.now(),
    password = "StageTwo123!";
  const names = {
    owner: `contrib_${suffix}`,
    reviewer: `review_${suffix}`,
    reader: `learn_${suffix}`,
    technician: `jobs_${suffix}`,
  };
  const register = async (username: string) => {
    const response = await context.request.post("/api/v1/auth/register", {
      data: { username, password, nickname: username },
    });
    expect(response.status()).toBe(201);
    return String((await response.json()).data.id);
  };
  const owner = await register(names.owner),
    reviewer = await register(names.reviewer);
  await register(names.reader);
  const technician = await register(names.technician);
  execFileSync(
    "python",
    ["e2e/grant-stage-two-roles.py", owner, reviewer, technician],
    { windowsHide: true, stdio: "pipe" },
  );
  const baseURL = test.info().project.use.baseURL as string;
  const reviewerContext = await browser.newContext({ baseURL }),
    readerContext = await browser.newContext({ baseURL }),
    technicianContext = await browser.newContext({ baseURL });
  const login = async (ctx: BrowserContext, username: string) => {
    const response = await ctx.request.post("/api/v1/auth/login", {
      data: { username, password },
    });
    expect(response.status()).toBe(200);
    return {
      Authorization: `Bearer ${(await response.json()).data.accessToken}`,
    };
  };
  await login(context, names.owner);
  const reviewerHeaders = await login(reviewerContext, names.reviewer);
  const readerHeaders = await login(readerContext, names.reader);
  await login(technicianContext, names.technician);
  const reviewPage = await reviewerContext.newPage(),
    readerPage = await readerContext.newPage(),
    jobsPage = await technicianContext.newPage();
  const errors: string[] = [];
  for (const tab of [page, reviewPage, readerPage, jobsPage])
    tab.on("pageerror", (error) => errors.push(error.message));
  const sourceTitle = `本草来源验收 ${suffix}`,
    title = `桂枝知识验收 ${suffix}`,
    original =
      "桂枝，为樟科植物肉桂的干燥嫩枝。此文本仅用于来源处理与审核闭环验收，不作为诊疗建议。";
  await page.goto("/contribute/sources/new");
  await page.getByLabel("资料标题", { exact: true }).fill(sourceTitle);
  await page.getByLabel("作者", { exact: true }).fill("隔离验收资料作者");
  await page.getByLabel("原始资料", { exact: true }).setInputFiles({
    name: "stage-two-source.txt",
    mimeType: "text/plain",
    buffer: Buffer.from(original, "utf8"),
  });
  await page.getByRole("button", { name: "保存来源资料", exact: true }).click();
  await expect(page).toHaveURL(/\/contribute\/sources\/\d+$/);
  const sourceId = page.url().split("/").at(-1)!;
  await page.getByRole("button", { name: "解析资料", exact: true }).click();
  await expect(
    page.locator(".stage-prose").filter({ hasText: original }).first(),
  ).toBeVisible({ timeout: 45000 });
  await page.getByRole("button", { name: "提交来源审核", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "撤回来源审核", exact: true }),
  ).toBeVisible();
  await expect(page.getByLabel("资料标题", { exact: true })).toHaveCount(0);
  const approve = async (target: Page, contentTitle: string, note: string) => {
    await target.goto("/reviews");
    await target.getByRole("link", { name: contentTitle, exact: true }).click();
    await target
      .getByRole("button", { name: "领取审核任务", exact: true })
      .click();
    await target.getByLabel("审核意见（必填）", { exact: true }).fill(note);
    await target
      .getByRole("button", { name: "确认审核结论", exact: true })
      .click();
    await expect(
      target.getByRole("status").filter({ hasText: "审核已通过" }),
    ).toBeVisible();
  };
  await approve(
    reviewPage,
    sourceTitle,
    "已核对书目信息、原始文件与解析片段，允许作为学习来源。",
  );
  await expect(
    reviewPage.getByRole("link", { name: "管理后台", exact: true }),
  ).toHaveCount(0);
  await page.goto(`/contribute/sources/${sourceId}`);
  await page
    .getByRole("checkbox", { name: /选择片段 1 整理为知识草稿/ })
    .check();
  await page
    .getByRole("button", { name: "将 1 个片段整理为草稿", exact: true })
    .click();
  await expect(page).toHaveURL(/\/contribute\/knowledge\/\d+$/);
  const resourceId = page.url().split("/").at(-1)!;
  await page.getByLabel("规范名称", { exact: true }).fill(title);
  await page.getByLabel("知识标题", { exact: true }).fill(title);
  await page
    .getByLabel("知识正文", { exact: true })
    .fill(original + "\n\n依据原文整理的已审核知识正文。");
  await page.getByLabel("别名", { exact: true }).fill(`验收别名${suffix}`);
  await page.getByRole("button", { name: "保存知识草稿", exact: true }).click();
  await expect(page.getByRole("status")).toContainText("知识草稿已保存");
  await page.getByRole("button", { name: "提交知识审核", exact: true }).click();
  await expect(page.getByLabel("知识正文", { exact: true })).toBeDisabled();
  await approve(
    reviewPage,
    title,
    "已核对来源已通过且有效、引文与正文一致，允许正式发布。",
  );
  await readerPage.goto("/knowledge");
  await readerPage
    .getByLabel("搜索知识", { exact: true })
    .fill(`验收别名${suffix}`);
  await readerPage.getByRole("button", { name: "搜索", exact: true }).click();
  await readerPage.getByRole("link", { name: title, exact: true }).click();
  await expect(
    readerPage.getByRole("heading", { name: title, exact: true }),
  ).toBeVisible();
  await expect(
    readerPage.getByRole("link", { name: sourceTitle, exact: true }),
  ).toBeVisible();
  await readerPage
    .getByRole("button", { name: "收藏知识", exact: true })
    .click();
  await expect(
    readerPage.getByRole("button", { name: "已收藏", exact: true }),
  ).toHaveAttribute("aria-pressed", "true");
  mkdirSync("../runtime/screenshots", { recursive: true });
  for (const width of [375, 1440]) {
    await readerPage.setViewportSize({ width, height: 1000 });
    expect(
      await readerPage.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBe(true);
    await readerPage.screenshot({
      path: `../runtime/screenshots/stage-two-knowledge-${width}.png`,
      fullPage: true,
    });
  }
  await readerPage
    .getByRole("link", { name: sourceTitle, exact: true })
    .click();
  await expect(
    readerPage.getByRole("heading", { name: sourceTitle, exact: true }),
  ).toBeVisible();
  await expect(
    readerPage.locator(".stage-prose").filter({ hasText: original }),
  ).toBeVisible();
  await readerPage.goto("/account");
  await expect(
    readerPage.getByRole("link", { name: title, exact: true }),
  ).toBeVisible();
  await readerPage.getByRole("tab", { name: "浏览历史", exact: true }).click();
  await expect(
    readerPage.getByRole("link", { name: title, exact: true }),
  ).toBeVisible();
  await page.goto(`/contribute/knowledge/${resourceId}`);
  await page.getByRole("button", { name: "创建知识新稿", exact: true }).click();
  await page
    .getByLabel("知识正文", { exact: true })
    .fill("尚未审核的新版本内容，不应覆盖正式版本。");
  await page.getByRole("button", { name: "保存知识草稿", exact: true }).click();
  await expect(page.getByRole("status")).toContainText("知识草稿已保存");
  await readerPage.goto(`/knowledge/${resourceId}`);
  await expect(readerPage.locator(".stage-prose").first()).toContainText(
    "依据原文整理的已审核知识正文",
  );
  await expect(readerPage.locator(".stage-prose").first()).not.toContainText(
    "尚未审核的新版本",
  );
  await jobsPage.goto("/admin/jobs");
  await expect(
    jobsPage.getByRole("heading", { name: "技术任务", exact: true }),
  ).toBeVisible();
  await expect(
    jobsPage.getByText("等待第四阶段索引服务", { exact: true }).first(),
  ).toBeVisible();
  await readerPage.goto("/reviews");
  await expect(
    readerPage.getByRole("heading", { name: "暂时无法访问", exact: true }),
  ).toBeVisible();
  const suspended = await reviewerContext.request.post(
    `/api/v1/source-documents/${sourceId}/suspend`,
    {
      headers: reviewerHeaders,
      data: { reason: "验收结束，停用隔离测试来源" },
    },
  );
  expect(suspended.status()).toBe(200);
  expect(
    (
      await readerContext.request.get(`/api/v1/knowledge/${resourceId}`, {
        headers: readerHeaders,
      })
    ).status(),
  ).toBe(404);
  expect(
    (
      await readerContext.request.get(`/api/v1/resources/${resourceId}`, {
        headers: readerHeaders,
      })
    ).status(),
  ).toBe(404);
  await readerPage.goto("/account");
  const unavailable = readerPage
    .locator(".content-entry")
    .filter({ hasText: "内容暂不可查看" });
  await expect(unavailable).toBeVisible();
  await expect(
    unavailable.getByRole("link", { name: title, exact: true }),
  ).toHaveCount(0);
  expect(errors).toEqual([]);
  await reviewerContext.close();
  await readerContext.close();
  await technicianContext.close();
});

test("structured herb and formula forms, comparison, CSV import and corrections use real endpoints", async ({
  browser,
  context,
  page,
}) => {
  test.setTimeout(180000);
  const suffix = Date.now(),
    password = "StageTwo123!";
  async function register(prefix: string) {
    const username = `${prefix}_${suffix}`;
    const response = await context.request.post("/api/v1/auth/register", {
      data: { username, password, nickname: username },
    });
    expect(response.status()).toBe(201);
    return { id: String((await response.json()).data.id), username };
  }
  const author = await register("facts"),
    reviewer = await register("factsreview"),
    technician = await register("factstech");
  execFileSync(
    "python",
    ["e2e/grant-stage-two-roles.py", author.id, reviewer.id, technician.id],
    { windowsHide: true, stdio: "pipe" },
  );
  const baseURL = test.info().project.use.baseURL as string;
  const reviewContext = await browser.newContext({ baseURL });
  async function login(ctx: BrowserContext, username: string) {
    const response = await ctx.request.post("/api/v1/auth/login", {
      data: { username, password },
    });
    expect(response.status()).toBe(200);
    return {
      Authorization: `Bearer ${(await response.json()).data.accessToken}`,
    };
  }
  const authorHeaders = await login(context, author.username),
    reviewHeaders = await login(reviewContext, reviewer.username);
  const sourceTitle = `结构化书目验收 ${suffix}`,
    herbTitle = `结构化药材 ${suffix}`,
    formulaTitle = `结构化方剂甲 ${suffix}`,
    formulaOther = `结构化方剂乙 ${suffix}`;
  const quote = "仅用于隔离验收的原文依据，不作为医学建议。";
  const sourceResponse = await context.request.post(
    "/api/v1/source-documents",
    {
      headers: authorHeaders,
      data: {
        title: sourceTitle,
        sourceType: "WEB",
        sourceUrl: `https://example.org/acceptance/${suffix}`,
        author: "隔离验收",
      },
    },
  );
  expect(sourceResponse.status()).toBe(200);
  const sourceId = String((await sourceResponse.json()).data.id);
  expect(
    (
      await context.request.post(
        `/api/v1/source-documents/${sourceId}/submit`,
        { headers: authorHeaders },
      )
    ).status(),
  ).toBe(200);
  async function approve(title: string) {
    let task: any;
    for (let next = 1; next <= 20; next++) {
      const response = await reviewContext.request.get(
        `/api/v1/reviews?status=PENDING&pageSize=100&page=${next}`,
        { headers: reviewHeaders },
      );
      expect(response.status()).toBe(200);
      const result = (await response.json()).data;
      task = result.items.find((row: any) => row.title === title);
      if (task || next * 100 >= result.total) break;
    }
    expect(task, `review task for ${title}`).toBeTruthy();
    const claimed = await reviewContext.request.post(
      `/api/v1/reviews/${task.id}/claim`,
      { headers: reviewHeaders, data: { lockVersion: task.lockVersion } },
    );
    expect(claimed.status()).toBe(200);
    expect(
      (
        await reviewContext.request.post(
          `/api/v1/reviews/${task.id}/decisions`,
          {
            headers: reviewHeaders,
            data: {
              lockVersion: (await claimed.json()).data.lockVersion,
              decision: "APPROVED",
              note: "已核对隔离验收来源、结构化事实和引用位置。",
            },
          },
        )
      ).status(),
    ).toBe(200);
  }
  await approve(sourceTitle);
  async function evidence() {
    await page.getByLabel("搜索来源", { exact: true }).fill(sourceTitle);
    await page.getByRole("button", { name: "查找来源", exact: true }).click();
    await page
      .getByRole("combobox", { name: "来源资料", exact: true })
      .selectOption({ label: sourceTitle });
    await page.getByLabel("位置说明", { exact: true }).fill("验收资料第1节");
    await page.getByLabel("引文", { exact: true }).fill(quote);
    await page.getByRole("button", { name: "添加依据", exact: true }).click();
  }
  await page.goto("/contribute/knowledge/new");
  await page
    .getByRole("combobox", { name: "知识类型", exact: true })
    .selectOption("HERB");
  await page.getByLabel("规范名称", { exact: true }).fill(herbTitle);
  await page.getByLabel("知识标题", { exact: true }).fill(herbTitle);
  await page.getByLabel("知识正文", { exact: true }).fill("隔离验收药材正文。");
  await page.getByLabel("药性", { exact: true }).fill("温");
  await page.getByLabel("药味", { exact: true }).fill("辛、甘");
  await page.getByLabel("来源与产地", { exact: true }).fill("验收植物来源说明");
  await page
    .getByLabel("注意事项", { exact: true })
    .fill("仅为结构测试，不用于用药。");
  await evidence();
  await page.getByRole("button", { name: "保存知识草稿", exact: true }).click();
  await expect(page).toHaveURL(/\/contribute\/knowledge\/\d+$/);
  const herbId = page.url().split("/").at(-1)!;
  await page.getByRole("button", { name: "提交知识审核", exact: true }).click();
  await expect(page.getByLabel("知识正文", { exact: true })).toBeDisabled();
  await approve(herbTitle);
  await page.goto("/contribute/knowledge/new");
  await page
    .getByRole("combobox", { name: "知识类型", exact: true })
    .selectOption("FORMULA");
  await page.getByLabel("规范名称", { exact: true }).fill(formulaTitle);
  await page.getByLabel("知识标题", { exact: true }).fill(formulaTitle);
  await page.getByLabel("知识正文", { exact: true }).fill("隔离验收方剂正文。");
  await page.getByLabel("方剂出处", { exact: true }).fill("隔离验收书目");
  await page.getByLabel("功效记载", { exact: true }).fill("资料中的功效原文");
  await page.getByLabel("主治记载", { exact: true }).fill("资料中的主治原文");
  await page.getByLabel("方剂注意事项", { exact: true }).fill("仅作学习对照。");
  await evidence();
  await page.getByLabel("查找药材", { exact: true }).fill(herbTitle);
  await page.getByRole("button", { name: "查找药材", exact: true }).click();
  await page.getByRole("button", { name: "添加组成", exact: true }).click();
  await page
    .getByRole("combobox", { name: "药材", exact: true })
    .selectOption(herbId);
  await page
    .getByRole("combobox", { name: "配伍角色", exact: true })
    .selectOption("JUN");
  await page.getByLabel("用量数值", { exact: true }).fill("3");
  await page.getByLabel("用量单位", { exact: true }).fill("两");
  await page.getByLabel("原文用量", { exact: true }).fill("三两");
  await page
    .getByRole("combobox", { name: "组成依据", exact: true })
    .selectOption("0");
  await page.getByLabel("查找关联知识", { exact: true }).fill(herbTitle);
  await page.getByRole("button", { name: "查找知识", exact: true }).click();
  await page.getByRole("button", { name: "添加关系", exact: true }).click();
  await page
    .getByRole("combobox", { name: "关联知识", exact: true })
    .selectOption(herbId);
  await page
    .getByRole("combobox", { name: "关系依据", exact: true })
    .selectOption("0");
  await page.getByLabel("关系说明", { exact: true }).fill("来源记载中的关联");
  await page.getByRole("button", { name: "保存知识草稿", exact: true }).click();
  await expect(page).toHaveURL(/\/contribute\/knowledge\/\d+$/);
  const formulaId = page.url().split("/").at(-1)!;
  await page.getByRole("button", { name: "提交知识审核", exact: true }).click();
  await expect(page.getByLabel("知识正文", { exact: true })).toBeDisabled();
  await approve(formulaTitle);
  const otherResponse = await context.request.post("/api/v1/knowledge", {
    headers: authorHeaders,
    data: {
      type: "FORMULA",
      canonicalName: formulaOther,
      title: formulaOther,
      body: "隔离验收方剂乙正文。",
      aliases: [],
      formula: {
        originText: "隔离验收书目",
        functionSummary: "另一功效记载",
        indicationSummary: "另一主治记载",
        precautions: "仅作学习对照。",
      },
      evidence: [
        {
          documentId: sourceId,
          locationLabel: "验收资料第1节",
          quoteText: quote,
        },
      ],
      ingredients: [],
      relations: [],
    },
  });
  expect(otherResponse.status()).toBe(201);
  const other = (await otherResponse.json()).data;
  expect(
    (
      await context.request.post(
        `/api/v1/revisions/${other.revisionId}/submit`,
        { headers: authorHeaders },
      )
    ).status(),
  ).toBe(200);
  await approve(formulaOther);
  await page.goto("/formulas/compare");
  await page.getByLabel("查找方剂", { exact: true }).fill(`结构化方剂`);
  await page.getByRole("button", { name: "查找", exact: true }).click();
  await page.getByRole("checkbox", { name: new RegExp(formulaTitle) }).check();
  await page.getByRole("checkbox", { name: new RegExp(formulaOther) }).check();
  await page
    .getByRole("button", { name: "比较所选 2 个方剂", exact: true })
    .click();
  await expect(page.getByRole("table")).toContainText("三两");
  for (const width of [375, 1440]) {
    await page.setViewportSize({ width, height: 1000 });
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBe(true);
    await page.screenshot({
      path: `../runtime/screenshots/stage-two-compare-${width}.png`,
      fullPage: true,
    });
  }
  await page.goto("/contribute/import");
  await page
    .getByLabel("CSV 内容", { exact: true })
    .fill(
      `类型,规范名称,标题,正文,别名\nCONCEPT,批量甲${suffix},批量甲${suffix},批量原文一,\nCONCEPT,批量乙${suffix},批量乙${suffix},批量原文二,`,
    );
  await page.getByRole("button", { name: "预览导入内容", exact: true }).click();
  await evidence();
  await page
    .getByRole("button", { name: "创建 2 项知识草稿", exact: true })
    .click();
  await expect(
    page.getByRole("link", { name: `编辑 批量甲${suffix}`, exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("link", { name: `编辑 批量乙${suffix}`, exact: true }),
  ).toBeVisible();
  await page.goto(`/knowledge/${formulaId}`);
  await page.getByText("发现内容问题？提交纠错", { exact: true }).click();
  await page
    .getByLabel("纠错内容", { exact: true })
    .fill("隔离验收纠错，请核对注意事项。");
  await page.getByRole("button", { name: "提交纠错", exact: true }).click();
  await expect(page.getByRole("status")).toContainText("纠错已提交");
  const reviewPage = await reviewContext.newPage();
  await reviewPage.goto("/reviews");
  await reviewPage.getByText("知识纠错处理", { exact: true }).click();
  const report = reviewPage
    .locator(".stage-list > li")
    .filter({ hasText: "隔离验收纠错，请核对注意事项。" });
  await expect(report).toBeVisible();
  await report
    .getByLabel("纠错处理意见", { exact: true })
    .fill("已核对，隔离验收结束。");
  await report.getByRole("button", { name: "标记已解决", exact: true }).click();
  await expect(report).toContainText("已解决");
  expect(
    (
      await reviewContext.request.post(
        `/api/v1/source-documents/${sourceId}/suspend`,
        {
          headers: reviewHeaders,
          data: { reason: "验收结束，停用结构化隔离测试来源" },
        },
      )
    ).status(),
  ).toBe(200);
  await reviewContext.close();
});
