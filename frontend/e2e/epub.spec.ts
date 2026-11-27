import {
  test,
  expect,
  type Browser,
  type BrowserContext,
  type Page,
} from "@playwright/test";
import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { epubFixture } from "./source-reading-fixtures";

test.use({
  // Windows occlusion detection can suspend headless Edge animation frames.
  // Keep normal Playwright stability checks while avoiding that test-host issue.
  launchOptions: { args: ["--disable-features=CalculateNativeWinOcclusion"] },
  actionTimeout: 15000,
  navigationTimeout: 30000,
  screenshot: "off",
  trace: "off",
});
import {
  createReadingActors,
  uploadWizard,
  waitReading,
  approveByTitle,
} from "./source-reading-helpers";

test("EPUB V2 wizard, immutable units, reader safety, rights, source approval and original hash", async ({
  browser,
  context,
  page,
}) => {
  test.setTimeout(240000);
  const baseURL = test.info().project.use.baseURL as string;
  const actors = await createReadingActors(browser, context, baseURL);
  const errors: string[] = [],
    external: string[] = [],
    dialogs: string[] = [];
  page.on("pageerror", (e) => errors.push(e.message));
  page.on("dialog", async (dialog) => {
    dialogs.push(dialog.type());
    await dialog.dismiss();
  });
  page.on("request", (request) => {
    if (new URL(request.url()).hostname === "source-reader.invalid")
      external.push(request.url());
  });
  try {
    const title = `Synthetic EPUB reader ${actors.suffix}`,
      bytes = epubFixture();
    const sourceId = await uploadWizard(
      page,
      {
        name: "security.EPUB",
        mimeType: "application/epub+zip",
        buffer: bytes,
      },
      title,
      true,
      false,
    );
    const reading = await waitReading(context, actors.ownerHeaders, sourceId);
    expect(reading.units.length).toBe(2);
    expect(reading.outline.map((n: { title: string }) => n.title)).toEqual([
      "First unit",
      "Second unit",
    ]);
    let response = await actors.reader.request.get(
      `/api/v1/source-documents/${sourceId}/reader`,
      { headers: actors.readerHeaders },
    );
    expect([403, 404]).toContain(response.status());
    const detail = await context.request.get(
      `/api/v1/source-documents/${sourceId}`,
      { headers: actors.ownerHeaders },
    );
    const fileId = (await detail.json()).data.fileId;
    response = await actors.reader.request.get(`/api/v1/files/${fileId}`, {
      headers: actors.readerHeaders,
    });
    expect([403, 404]).toContain(response.status());
    await page.goto(`/sources/${sourceId}/read`);
    await expect(page.locator(".reader-content")).toContainText(
      "Canonical first paragraph",
    );
    await expect(page.locator(".reader-workspace")).toHaveCount(0);
    await expect(
      page.locator(".reader-content script,.reader-content iframe"),
    ).toHaveCount(0);
    expect(
      await page.evaluate(
        () =>
          (window as unknown as { sourceReaderAttack?: boolean })
            .sourceReaderAttack,
      ),
    ).toBeUndefined();
    expect(external).toEqual([]);
    expect(dialogs).toEqual([]);
    await page
      .getByLabel("在本书中搜索", { exact: true })
      .fill("Reliable second");
    await page.getByRole("button", { name: "搜索全文", exact: true }).click();
    await page.locator(".reader-hits button").first().click();
    await expect(page.locator(".reader-content mark")).toContainText(
      "Reliable second",
    );
    await page
      .getByRole("combobox", { name: "阅读单元", exact: true })
      .selectOption(reading.units[0].id);
    await expect(page.locator(".reader-content")).toContainText(
      "Canonical first paragraph",
    );
    await page.getByRole("combobox", { name: "正文字号", exact: true }).selectOption("22");
    await expect
      .poll(async () => {
        const r = await context.request.get(
          `/api/v1/source-documents/${sourceId}/reading-progress?editionId=${reading.edition.id}`,
          { headers: actors.ownerHeaders },
        );
        expect(r.status()).toBe(200);
        return (await r.json()).data?.fontSize;
      })
      .toBe(22);
    await page.reload();
    await expect(page.getByRole("combobox", { name: "正文字号", exact: true })).toHaveValue(
      "22",
    );
    for (const width of [1440, 1280, 768, 390]) {
      await page.setViewportSize({ width, height: 1000 });
      expect(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth,
        ),
      ).toBe(true);
      if (width === 390) {
        await page
          .getByRole("button", { name: "目录与搜索", exact: true })
          .click();
        await expect(
          page.getByRole("dialog", { name: "目录与全文搜索" }),
        ).toBeVisible();
        await page.keyboard.press("Escape");
        await expect(
          page.getByRole("button", { name: "目录与搜索", exact: true }),
        ).toBeFocused();
      }
      mkdirSync("../runtime/source-reading-acceptance", { recursive: true });
      await page.screenshot({
        path: `../runtime/source-reading-acceptance/synthetic-epub-${width}.png`,
        fullPage: true,
      });
    }
    await page.setViewportSize({ width: 1440, height: 1000 });
    await page.goto(`/sources/${sourceId}`);
    await page
      .getByRole("button", { name: "提交来源审核", exact: true })
      .click();
    await expect(
      page.getByRole("button", { name: "撤回来源审核", exact: true }),
    ).toBeVisible();
    await approveByTitle(actors.reviewer, title, true);
    response = await actors.reader.request.get(
      `/api/v1/source-documents/${sourceId}/reader`,
      { headers: actors.readerHeaders },
    );
    expect(response.status()).toBe(200);
    response = await actors.reader.request.get(
      `/api/v1/source-documents/${sourceId}/file`,
      { headers: actors.readerHeaders },
    );
    expect([403, 404]).toContain(response.status());
    response = await actors.reader.request.get(`/api/v1/files/${fileId}`, {
      headers: actors.readerHeaders,
    });
    expect([403, 404]).toContain(response.status());
    response = await context.request.get(
      `/api/v1/source-documents/${sourceId}/file`,
      { headers: actors.ownerHeaders },
    );
    expect(response.status()).toBe(200);
    expect(
      createHash("sha256")
        .update(await response.body())
        .digest("hex"),
    ).toBe(createHash("sha256").update(bytes).digest("hex"));
    for (const [name, buffer] of [
      ["encrypted.epub", epubFixture(true)],
      ["fake.epub", Buffer.from("not an epub")],
    ] as const) {
      response = await context.request.post("/api/v1/files", {
        headers: actors.ownerHeaders,
        multipart: { file: { name, mimeType: "application/epub+zip", buffer } },
      });
      expect(response.status()).toBe(400);
    }
    if (process.env.EPUB_ACCEPTANCE_FILE) {
      const original = readFileSync(process.env.EPUB_ACCEPTANCE_FILE);
      const actualId = await uploadWizard(
        page,
        process.env.EPUB_ACCEPTANCE_FILE,
        `Private actual EPUB ${actors.suffix}`,
      );
      const actual = await waitReading(context, actors.ownerHeaders, actualId);
      expect(actual.units.length).toBeGreaterThan(0);
      const downloaded = await context.request.get(
        `/api/v1/source-documents/${actualId}/file`,
        { headers: actors.ownerHeaders },
      );
      expect(downloaded.status()).toBe(200);
      expect(
        createHash("sha256")
          .update(await downloaded.body())
          .digest("hex"),
      ).toBe(createHash("sha256").update(original).digest("hex"));
      writeFileSync(
        "../runtime/source-reading-acceptance/actual-book-result.json",
        JSON.stringify({
          sourceId: actualId,
          readingEditionId: actual.edition.id,
          unitCount: actual.units.length,
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
            "EPUB_ACCEPTANCE_FILE was not supplied; generated security fixture is not real-textbook acceptance.",
        });
    expect(errors).toEqual([]);
    expect(external).toEqual([]);
    expect(dialogs).toEqual([]);
  } finally {
    await actors.reviewer.close();
    await actors.reader.close();
    await actors.other.close();
  }
});
