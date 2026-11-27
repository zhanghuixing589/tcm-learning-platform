import {
  expect,
  type Browser,
  type BrowserContext,
  type Page,
} from "@playwright/test";
import { execFileSync } from "node:child_process";
export async function createReadingActors(
  browser: Browser,
  context: BrowserContext,
  baseURL: string,
) {
  const suffix = Date.now(),
    password = "Reader123!Ab";
  const names = ["owner", "reviewer", "reader", "other", "technician"].map(
    (role) => `read_${role}_${suffix}`,
  );
  const ids: string[] = [];
  for (const username of names) {
    const response = await context.request.post("/api/v1/auth/register", {
      data: { username, password, nickname: username },
    });
    expect(response.status()).toBe(201);
    ids.push(String((await response.json()).data.id));
  }
  execFileSync(
    "python",
    ["e2e/grant-stage-two-roles.py", ids[0]!, ids[1]!, ids[4]!],
    { windowsHide: true, stdio: "pipe" },
  );
  execFileSync(
    "python",
    ["e2e/grant-stage-two-roles.py", ids[3]!, ids[1]!, ids[4]!],
    { windowsHide: true, stdio: "pipe" },
  );
  const reviewer = await browser.newContext({ baseURL }),
    reader = await browser.newContext({ baseURL }),
    other = await browser.newContext({ baseURL });
  const login = async (ctx: BrowserContext, name: string) => {
    const response = await ctx.request.post("/api/v1/auth/login", {
      data: { username: name, password },
    });
    expect(response.status()).toBe(200);
    return {
      Authorization: `Bearer ${(await response.json()).data.accessToken}`,
    };
  };
  const ownerHeaders = await login(context, names[0]!),
    reviewerHeaders = await login(reviewer, names[1]!),
    readerHeaders = await login(reader, names[2]!),
    otherHeaders = await login(other, names[3]!);
  return {
    suffix,
    ids,
    names,
    ownerHeaders,
    reviewerHeaders,
    readerHeaders,
    otherHeaders,
    reviewer,
    reader,
    other,
  };
}
export async function uploadWizard(
  page: Page,
  file: string | { name: string; mimeType: string; buffer: Buffer },
  title: string,
  publicReading = false,
  publicDownload = false,
) {
  await page.goto("/contribute/sources/new");
  await page.getByLabel("选择资料文件", { exact: true }).setInputFiles(file);
  await expect(page.getByLabel("书目标题", { exact: true })).toBeVisible();
  await expect(
    page.getByRole("button", { name: "下一步：授权与确认", exact: true }),
  ).toBeEnabled();
  await page.getByLabel("书目标题", { exact: true }).fill(title);
  await page
    .getByRole("button", { name: "下一步：授权与确认", exact: true })
    .click();
  await page
    .getByRole("combobox", { name: "许可类型", exact: true })
    .selectOption(publicReading ? "PUBLIC_DOMAIN" : "AUTHORIZED");
  await page
    .getByLabel("版权或授权依据", { exact: true })
    .fill(
      publicReading?"Synthetic acceptance fixture; no real textbook license is inferred.":"User-supplied file authorized for private processing acceptance only; public rights are not inferred.",
    );
  await page
    .getByLabel("授权范围", { exact: true })
    .fill(
      publicReading
        ? "Fixture may be publicly read and cited for testing."
        : "Private processing acceptance only; no public distribution.",
    );
  await page
    .getByLabel("公开阅读全文", { exact: true })
    .setChecked(publicReading);
  await page
    .getByLabel("公开下载原文件", { exact: true })
    .setChecked(publicDownload);
  await page.getByLabel("展示引文", { exact: true }).setChecked(publicReading);
  await page.getByRole("button", { name: "确认并解析", exact: true }).click();
  await expect(page).toHaveURL(/\/sources\/\d+$/);
  return page.url().split("/").at(-1)!;
}
export async function waitReading(
  context: BrowserContext,
  headers: Record<string, string>,
  id: string,
) {
  await expect
    .poll(
      async () => {
        const response = await context.request.get(
          `/api/v1/source-documents/${id}`,
          { headers },
        );
        expect(response.status()).toBe(200);
        return (await response.json()).data.readingEditionId;
      },
      { timeout: 120000 },
    )
    .toBeTruthy();
  const response = await context.request.get(
    `/api/v1/source-documents/${id}/reader`,
    { headers },
  );
  expect(response.status()).toBe(200);
  return (await response.json()).data;
}
export async function approveByTitle(
  ctx: BrowserContext,
  title: string,
  source = false,
) {
  const page = await ctx.newPage();
  await page.goto("/reviews");
  await page.getByRole("link", { name: title, exact: true }).click();
  await page.getByRole("button", { name: "领取审核任务", exact: true }).click();
  if (source) {
    const use = page.getByRole("combobox", { name: "批准用途", exact: true });
    await expect(use).toBeVisible();
    await use.selectOption("TEXT_CITABLE");
    await expect(page.getByRole("group", { name: "批准的可靠文字单元", exact: true })).toBeVisible();
    for (const box of await page.locator("form input[type=checkbox]:enabled").all())
      await box.check();
  }
  await page
    .getByLabel("审核意见（必填）", { exact: true })
    .fill(
      "Synthetic fixture checked against canonical source and approved scope.",
    );
  await page.getByRole("button", { name: "确认审核结论", exact: true }).click();
  await expect(
    page.getByRole("status").filter({ hasText: "审核已通过" }),
  ).toBeVisible();
  await page.close();
}
