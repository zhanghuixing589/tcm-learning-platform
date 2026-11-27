import { test, expect, type Page } from "@playwright/test";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { createHash } from "node:crypto";
import {
  epubFixture,
  docxFixture,
  mixedPdfFixture,
} from "./source-reading-fixtures";
import {
  createReadingActors,
  uploadWizard,
  waitReading,
  approveByTitle,
} from "./source-reading-helpers";
test.use({
  launchOptions: { args: ["--disable-features=CalculateNativeWinOcclusion"] },
  actionTimeout: 15000,
  navigationTimeout: 30000,
  screenshot: "off",
  trace: "off",
});
async function selectCanonical(page: Page) {
  await page
    .locator(".reader-content [data-block-id]")
    .filter({ hasText: "Canonical first paragraph" })
    .evaluate((element) => {
      const walker = document.createTreeWalker(element, NodeFilter.SHOW_TEXT),
        first = walker.nextNode()!;
      const range = document.createRange();
      range.setStart(first, 0);
      range.setEnd(first, (first.textContent || "").length);
      const selection = window.getSelection()!;
      selection.removeAllRanges();
      selection.addRange(range);
    });
  await page.locator(".reader-content").dispatchEvent("pointerup");
  await expect(page.getByRole("toolbar", { name: "选文操作" })).toBeVisible();
}

test("actual mixed PDF, DOCX and TXT processing obeys per-unit capabilities and preview-only public delivery", async ({
  browser,
  context,
  page,
}) => {
  test.setTimeout(360000);
  const actors = await createReadingActors(
    browser,
    context,
    test.info().project.use.baseURL as string,
  );
  try {
    for (const fixture of [
      {
        name: "sample.docx",
        mimeType:
          "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        buffer: docxFixture(),
      },
      {
        name: "sample.txt",
        mimeType: "text/plain",
        buffer: Buffer.from(
          "Text section one.\n\nText section two with reliable text.",
          "utf8",
        ),
      },
    ]) {
      const id = await uploadWizard(
        page,
        fixture,
        `Synthetic ${fixture.name} ${actors.suffix}`,
      );
      const reader = await waitReading(context, actors.ownerHeaders, id);
      expect(reader.units.length).toBeGreaterThan(0);
      await page.goto(`/sources/${id}/read`);
      await expect(
        page.locator(".reader-content [data-block-id]").first(),
      ).toBeVisible();
      if (fixture.name.endsWith(".txt")) {
        expect(reader.outline).toEqual([]);
        expect(
          reader.units.every(
            (u: { unitType: string }) => u.unitType === "TEXT_SECTION",
          ),
        ).toBe(true);
      }
    }
    const title = `Synthetic mixed PDF ${actors.suffix}`,
      id = await uploadWizard(
        page,
        {
          name: "mixed.pdf",
          mimeType: "application/pdf",
          buffer: mixedPdfFixture(),
        },
        title,
        true,
        false,
      );
    const reader = await waitReading(context, actors.ownerHeaders, id);
    expect(reader.units.length).toBe(2);
    expect(reader.units[0].capabilities.searchable).toBe(true);
    expect(reader.units[1].capabilities.searchable).toBe(false);
    expect(reader.units[1].capabilities.preciseCitationSupported).toBe(false);
    expect(reader.units[1].availableReadingModes).toContain("ORIGINAL_PREVIEW");
    await page.goto(`/sources/${id}/read`);
    await page.getByRole("checkbox", { name: "原始 PDF 页面", exact: true }).check();
    await expect(page.locator(".textLayer")).toContainText("Reliable PDF");
    const originalCanvas = page.getByLabel("PDF 原始页面", { exact: true });
    const pixels = async () => originalCanvas.evaluate((canvas: HTMLCanvasElement) => {
      const values = canvas.getContext("2d")!.getImageData(0, 0, canvas.width, canvas.height).data;
      let hash = 0; for (let i = 0; i < values.length; i += 4) hash = (Math.imul(hash, 31) + values[i]!) | 0;
      return hash;
    });
    const firstPagePixels = await pixels();
    await page.getByRole("combobox", { name: "阅读单元", exact: true }).selectOption(reader.units[1].id);
    await expect(page.locator(".reader-content h2")).toHaveText(reader.units[1].title);
    await expect(page.getByText("正在绘制原始页面…", { exact: true })).toHaveCount(0);
    await expect.poll(async () => (await pixels()) !== firstPagePixels).toBe(true);
    await page.goto(`/sources/${id}`);
    await page
      .getByRole("button", { name: "提交来源审核", exact: true })
      .click();
    await approveByTitle(actors.reviewer, title, true);
    const publicPage = await actors.reader.newPage(),
      originalRequests: string[] = [],
      errors: string[] = [];
    publicPage.on("pageerror", (e) => errors.push(e.message));
    publicPage.on("request", (r) => {
      if (r.url().endsWith(`/source-documents/${id}/file`))
        originalRequests.push(r.url());
    });
    await publicPage.goto(`/sources/${id}/read`);
    await publicPage
      .getByRole("combobox", { name: "阅读单元", exact: true })
      .selectOption(reader.units[1].id);
    await expect(publicPage.locator(".reader-preview img")).toBeVisible();
    await expect(
      publicPage.getByText("当前单元不支持可靠文字引用", { exact: false }),
    ).toBeVisible();
    await expect(
      publicPage.getByLabel("原始 PDF 页面", { exact: true }),
    ).toHaveCount(0);
    await publicPage
      .getByLabel("在本书中搜索", { exact: true })
      .fill("Reliable PDF");
    await publicPage
      .getByRole("button", { name: "搜索全文", exact: true })
      .click();
    await expect(
      publicPage.locator(".reader-hits button").first(),
    ).toBeVisible();
    expect(originalRequests).toEqual([]);
    expect(errors).toEqual([]);
    await publicPage.close();
    if (process.env.SOURCE_DOC_ACCEPTANCE_FILE) {
      const file = process.env.SOURCE_DOC_ACCEPTANCE_FILE,
        original = readFileSync(file),
        actualId = await uploadWizard(
          page,
          file,
          `Private DOC ${actors.suffix}`,
        );
      const reading = await waitReading(context, actors.ownerHeaders, actualId);
      expect(reading.edition.format).toBe("DOC");
      const download = await context.request.get(
        `/api/v1/source-documents/${actualId}/file`,
        { headers: actors.ownerHeaders },
      );
      expect(download.status()).toBe(200);
      expect(
        createHash("sha256")
          .update(await download.body())
          .digest("hex"),
      ).toBe(createHash("sha256").update(original).digest("hex"));
      mkdirSync("../runtime/source-reading-acceptance", { recursive: true });
      writeFileSync(
        "../runtime/source-reading-acceptance/actual-doc-result.json",
        JSON.stringify({
          sourceId: actualId,
          readingEditionId: reading.edition.id,
          originalHashMatched: true,
          publiclyApproved: false,
        }),
      );
    } else
      test
        .info()
        .annotations.push({
          type: "not-verified",
          description:
            "SOURCE_DOC_ACCEPTANCE_FILE was not supplied; no real binary DOC is claimed as accepted.",
        });
  } finally {
    await actors.reviewer.close();
    await actors.reader.close();
    await actors.other.close();
  }
});

test("exact excerpts persist, all six shared forms save, and independent proposals publish without owner-draft contamination", async ({
  browser,
  context,
  page,
}) => {
  test.setTimeout(420000);
  const actors = await createReadingActors(
      browser,
      context,
      test.info().project.use.baseURL as string,
    ),
    errors: string[] = [];
  page.on("pageerror", (e) => errors.push(e.message));
  try {
    const sourceTitle = `Synthetic evidence source ${actors.suffix}`,
      sourceId = await uploadWizard(
        page,
        {
          name: "evidence.epub",
          mimeType: "application/epub+zip",
          buffer: epubFixture(),
        },
        sourceTitle,
        true,
        false,
      );
    const reader = await waitReading(context, actors.ownerHeaders, sourceId);
    await page.reload();
    await page
      .getByRole("button", { name: "提交来源审核", exact: true })
      .click();
    await approveByTitle(actors.reviewer, sourceTitle, true);
    await page.goto(`/sources/${sourceId}/read`);
    await selectCanonical(page);
    const excerptResponse = page.waitForResponse(
      (r) =>
        r.url().endsWith(`/source-documents/${sourceId}/excerpts`) &&
        r.request().method() === "POST",
    );
    await page
      .getByRole("button", { name: "仅存个人摘录", exact: true })
      .click();
    expect([200, 201]).toContain((await excerptResponse).status());
    await expect(page.getByRole("status")).toContainText("个人摘录已保存");
    const excerpts = await context.request.get("/api/v1/me/source-excerpts", {
      headers: actors.ownerHeaders,
    });
    expect(excerpts.status()).toBe(200);
    const saved = (await excerpts.json()).data.find(
      (e: { documentId: string }) => e.documentId === sourceId,
    );
    expect(saved.readingEditionId).toBe(reader.edition.id);
    expect(saved.quoteText).toContain("😀");
    expect(saved.locator.ranges.length).toBeGreaterThan(0);
    await page.setViewportSize({ width: 390, height: 844 });
    await selectCanonical(page);
    await page.getByRole("toolbar", { name: "选文操作" })
      .getByRole("button", { name: "整理为知识", exact: true }).click();
    const mobileWorkspace = page.getByRole("dialog", { name: "知识整理工作区" });
    await expect(mobileWorkspace).toBeVisible();
    const readingDraftTitle = `Synthetic reading draft ${actors.suffix}`;
    await mobileWorkspace.getByLabel("规范名称", { exact: true }).fill(readingDraftTitle);
    await mobileWorkspace.getByLabel("知识标题", { exact: true }).fill(readingDraftTitle);
    await mobileWorkspace.getByLabel("知识正文", { exact: true })
      .fill("Knowledge organized manually from the selected synthetic passage.");
    const mobileDraftResponse = page.waitForResponse(r =>
      r.url().endsWith("/api/v1/knowledge") && r.request().method() === "POST");
    await mobileWorkspace.getByRole("button", { name: "保存知识草稿", exact: true }).click();
    const mobileDraftResult = await mobileDraftResponse;
    expect([200, 201]).toContain(mobileDraftResult.status());
    const mobileDraft = (await mobileDraftResult.json()).data;
    expect(mobileDraft.evidence[0].quoteHash).toBe(saved.quoteHash);
    expect(mobileDraft.evidence[0].locator).toEqual(saved.locator);
    await expect(page.getByRole("status")).toContainText("知识草稿已保存");
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    mkdirSync("../runtime/source-reading-acceptance", { recursive: true });
    await page.screenshot({ path: "../runtime/source-reading-acceptance/synthetic-mobile-curation.png" });
    await mobileWorkspace.getByRole("button", { name: "收起", exact: true }).click();
    await page.setViewportSize({ width: 1440, height: 1000 });
    const secondTitle = `Private multi-book excerpt ${actors.suffix}`,
      secondId = await uploadWizard(
        page,
        {
          name: "second.txt",
          mimeType: "text/plain",
          buffer: Buffer.from(
            "Canonical first paragraph from another source.",
            "utf8",
          ),
        },
        secondTitle,
      );
    await waitReading(context, actors.ownerHeaders, secondId);
    await page.goto(`/sources/${secondId}/read`);
    await selectCanonical(page);
    await page
      .getByRole("button", { name: "仅存个人摘录", exact: true })
      .click();
    await expect(page.getByRole("status")).toContainText("个人摘录已保存");
    const drafts: {
      id: string;
      revisionId: string;
      type: string;
      title: string;
    }[] = [];
    for (const type of [
      "CONCEPT",
      "HERB",
      "FORMULA",
      "EFFECT",
      "SYMPTOM",
      "MERIDIAN",
    ]) {
      await page.goto("/contribute/knowledge/new");
      await page.getByRole("combobox", { name: "知识类型", exact: true }).selectOption(type);
      const title = `Synthetic ${type} ${actors.suffix}`;
      await page.getByLabel("规范名称", { exact: true }).fill(title);
      await page.getByLabel("知识标题", { exact: true }).fill(title);
      await page
        .getByLabel("知识正文", { exact: true })
        .fill("Manually organized test knowledge; not clinical advice.");
      if (type === "HERB") {
        await page.getByLabel("药性", { exact: true }).fill("test nature");
        await page.getByLabel("药味", { exact: true }).fill("test flavor");
      }
      if (type === "FORMULA")
        await page
          .getByLabel("方剂出处", { exact: true })
          .fill("Synthetic test source");
      await page
        .getByText("从个人摘录添加依据（跨资料）", { exact: true })
        .click();
      await page
        .locator("details li")
        .filter({ hasText: sourceTitle })
        .getByRole("button", { name: "加入本稿依据", exact: true })
        .first()
        .click();
      if (type === "HERB")
        await page
          .locator("details li")
          .filter({ hasText: secondTitle })
          .getByRole("button", { name: "加入本稿依据", exact: true })
          .first()
          .click();
      const response = page.waitForResponse(
        (r) =>
          r.url().endsWith("/api/v1/knowledge") &&
          r.request().method() === "POST",
      );
      await page
        .getByRole("button", { name: "保存知识草稿", exact: true })
        .click();
      const result = await response;
      expect([200, 201]).toContain(result.status());
      const data = (await result.json()).data;
      expect(data.type).toBe(type);
      expect((await result.request().postDataJSON()).evidence[0].excerptId).toBe(saved.id);
      expect(data.evidence[0].readingEditionId).toBe(saved.readingEditionId);
      expect(data.evidence[0].quoteHash).toBe(saved.quoteHash);
      expect(data.evidence[0].quoteText).toBe(saved.quoteText);
      expect(data.evidence[0].locator).toEqual(saved.locator);
      if (type === "HERB")
        expect(
          new Set(
            data.evidence.map((e: { documentId: string }) => e.documentId),
          ).size,
        ).toBe(2);
      drafts.push({ id: data.id, revisionId: data.revisionId, type, title });
      await expect(page).toHaveURL(
        new RegExp(`/contribute/knowledge/${data.id}$`),
      );
    }
    const concept = drafts[0]!;
    await page.goto(`/contribute/knowledge/${concept.id}`);
    await page
      .getByRole("button", { name: "提交知识审核", exact: true })
      .click();
    await approveByTitle(actors.reviewer, concept.title);
    const original = await context.request.get(
      `/api/v1/knowledge/${concept.id}`,
      { headers: actors.ownerHeaders },
    );
    const base = (await original.json()).data;
    const otherPage = await actors.other.newPage();
    otherPage.on("pageerror", (e) => errors.push(e.message));
    await otherPage.goto(`/knowledge/${concept.id}`);
    await otherPage
      .getByRole("link", { name: "提出独立修订建议", exact: true })
      .click();
    await otherPage
      .getByLabel("知识正文", { exact: true })
      .fill(
        "Independent collaborator suggestion verified against the same permitted excerpt.",
      );
    const proposalResponse = otherPage.waitForResponse(
      (r) =>
        r.url().endsWith(`/knowledge/${concept.id}/proposals`) &&
        r.request().method() === "POST",
    );
    await otherPage
      .getByRole("button", { name: "保存修订建议草稿", exact: true })
      .click();
    const proposed = await proposalResponse;
    expect([200, 201]).toContain(proposed.status());
    const proposal = (await proposed.json()).data;
    expect(proposal.baseRevisionId).toBe(base.revisionId);
    const privateResponse = await context.request.get(
      `/api/v1/knowledge-proposals/${proposal.id}`,
      { headers: actors.ownerHeaders },
    );
    expect([403, 404]).toContain(privateResponse.status());
    const stillBase = await context.request.get(
      `/api/v1/knowledge/${concept.id}`,
      { headers: actors.ownerHeaders },
    );
    expect((await stillBase.json()).data.revisionId).toBe(base.revisionId);
    await otherPage
      .getByRole("button", { name: "提交修订建议审核", exact: true })
      .click();
    await approveByTitle(actors.reviewer, concept.title);
    const published = await context.request.get(
      `/api/v1/knowledge/${concept.id}`,
      { headers: actors.ownerHeaders },
    );
    const current = (await published.json()).data;
    expect(current.ownerId).toBe(base.ownerId);
    expect(current.revisionId).not.toBe(base.revisionId);
    expect(current.body).toContain("Independent collaborator suggestion");
    await otherPage.close();
    await page.goto(`/knowledge/${concept.id}`);
    await page.locator(".evidence-open").first().click();
    await expect(
      page.getByRole("heading", { name: "核对来源引文", exact: true }),
    ).toBeVisible();
    await expect(
      page.getByText(saved.quoteText, { exact: true }),
    ).toBeVisible();
    await page
      .getByRole("link", { name: "打开固定阅读版本并定位原文", exact: true })
      .click();
    await expect(page).toHaveURL(new RegExp(`editionId=${reader.edition.id}`));
    await expect(page.locator(".reader-content mark").first()).toBeVisible();
    expect(errors).toEqual([]);
  } finally {
    await actors.reviewer.close();
    await actors.reader.close();
    await actors.other.close();
  }
});
