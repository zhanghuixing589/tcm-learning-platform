import { test, expect, type BrowserContext, type Page, type APIResponse } from '@playwright/test';
import { readFileSync, mkdirSync } from 'node:fs';
import { resolve, sep } from 'node:path';
import { randomUUID } from 'node:crypto';
import type { Article, Post, Section } from '../src/modules/community/types';

// This write scenario is opt-in and must never run against the ordinary development site.
test.skip(!process.env.COMMUNITY_UI_ACCEPTANCE_ROOT, 'Requires the explicitly isolated community acceptance environment.');
test.use({ actionTimeout: 15000, navigationTimeout: 30000 });

test('community discovery, publishing, review and conversation retain their real workflows', async ({ browser, baseURL }) => {
  test.setTimeout(240000);
  const root = resolve(process.env.COMMUNITY_UI_ACCEPTANCE_ROOT!);
  expect(root.startsWith(resolve('../runtime') + sep)).toBe(true);
  expect(baseURL).toBe('http://127.0.0.1:5175');
  const state = JSON.parse(readFileSync(resolve(root, 'runtime/dev-mysql.json'), 'utf8').replace(/^\uFEFF/, ''));
  const ordinary = JSON.parse(readFileSync('../runtime/dev-mysql.json', 'utf8').replace(/^\uFEFF/, ''));
  expect(state.port).not.toBe(ordinary.port);
  expect(resolve(state.scratch).startsWith(resolve(root, 'runtime') + sep)).toBe(true);
  const credentials = JSON.parse(readFileSync(resolve(root, 'runtime/e2e-admin.json'), 'utf8').replace(/^\uFEFF/, ''));
  const errors: string[] = [];
  const contexts: BrowserContext[] = [];
  const suffix = Date.now();
  const out = '../runtime/community-ux'; mkdirSync(out, { recursive: true });
  async function data<T>(response: APIResponse, status = 200): Promise<T> {
    expect(response.status()).toBe(status); return (await response.json()).data;
  }
  async function actor(login: { username: string; password: string }) {
    const context = await browser.newContext({ baseURL, viewport: { width: 1440, height: 1000 } }); contexts.push(context);
    const auth = await data<{ accessToken: string }>(await context.request.post('/api/v1/auth/login', { data: login }));
    const page = await context.newPage(); page.on('pageerror', error => errors.push(error.message)); page.on('dialog', dialog => dialog.accept());
    return { context, page, headers: { Authorization: 'Bearer ' + auth.accessToken } };
  }
  async function capture(page: Page, name: string) {
    for (const width of [1440, 1280, 768, 390]) {
      await page.setViewportSize({ width, height: 1000 });
      await page.evaluate(() => window.scrollTo(0, 0));
      await expect(page.locator('vite-error-overlay')).toHaveCount(0);
      expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width + 2);
      await page.screenshot({ path: `${out}/${name}-${width}.png`, fullPage: true });
    }
    await page.setViewportSize({ width: 1440, height: 1000 });
  }
  try {
    const admin = await actor(credentials); // The isolated bootstrap account is checked before any writes.
    async function register(role: string, nickname: string) {
      const credentials = { username: `communityui_${role}_${suffix}`, password: 'Community1!' };
      const user = await data<{ id: string }>(await admin.context.request.post('/api/v1/auth/register', { data: { ...credentials, nickname } }), 201);
      if (role !== 'reader') await data(await admin.context.request.put(`/api/v1/admin/users/${user.id}/roles`, { headers: admin.headers, data: { roles: ['LEARNER', role === 'writer' ? 'CONTRIBUTOR' : 'REVIEWER'] } }));
      return { ...user, credentials };
    }
    const writerUser = await register('writer', '隔离验收分享者');
    const readerUser = await register('reader', '隔离验收交流者');
    const reviewerUser = await register('reviewer', '隔离验收审核者');
    const writer = await actor(writerUser.credentials), reader = await actor(readerUser.credentials), reviewer = await actor(reviewerUser.credentials);
    const section = await data<Section>(await admin.context.request.post('/api/v1/admin/community/sections', { headers: { ...admin.headers, 'Idempotency-Key': randomUUID() }, data: { name: `本草共读 · 验收${suffix}`, code: `community-ui-${suffix}`, description: '仅用于独立数据库的社区界面与交互验收。', rulesText: '所有内容均为合成验收示例，不作为医疗建议。', sortOrder: 0 } }), 201);
    console.log('Prepared isolated actors and section.');

    await test.step('real sections, membership and the discussion publication form', async () => {
      console.log('Checking membership and discussion editor.');
      for (const actor of [writer, reader]) {
        await actor.page.goto(`/community/sections/${section.id}`);
        await actor.page.getByRole('button', { name: '加入分区', exact: true }).click();
        await expect(actor.page.getByText(/位成员，已加入/)).toBeVisible();
      }
      await reader.page.getByLabel('我的分区').check();
      await expect(reader.page.getByRole('navigation', { name: '社区分区' }).getByRole('link', { name: section.name, exact: true })).toBeVisible();
      await writer.page.getByRole('link', { name: '在本区发布讨论', exact: true }).click();
      await expect(writer.page.getByLabel('讨论分区')).toHaveValue(section.id);
      await writer.page.locator('#discussion-title').fill(`阅读古籍时，你怎样记录出处？ ${suffix}`);
      await writer.page.locator('#discussion-body').fill('## 从一次阅读开始\n\n这是隔离环境的验收示例，不作为医学建议。我会先记录书名、版本与页码，再写下自己的疑问。\n\n### 想听听你的方法\n\n遇到不同版本的表述，你会怎样组织阅读笔记？');
      await writer.page.reload();
      await writer.page.getByRole('button', { name: '恢复草稿', exact: true }).click();
      await expect(writer.page.locator('#discussion-title')).toHaveValue(`阅读古籍时，你怎样记录出处？ ${suffix}`);
      await expect(writer.page.locator('#discussion-body')).toHaveValue(/从一次阅读开始/);
      await writer.page.getByRole('button', { name: '分屏', exact: true }).click();
      await expect(writer.page.getByRole('region', { name: '正文预览' }).getByRole('heading', { name: '从一次阅读开始', exact: true })).toBeVisible();
      await writer.page.getByRole('button', { name: '编辑', exact: true }).click();
      await writer.page.locator('#discussion-body').focus();
      await writer.page.locator('#discussion-body').evaluate((node: HTMLTextAreaElement) => node.setSelectionRange(3, 9));
      await writer.page.getByRole('button', { name: '加粗', exact: true }).click();
      // Restore the canonical source after exercising selection editing.
      await writer.page.locator('#discussion-body').fill('## 从一次阅读开始\n\n这是隔离环境的验收示例，不作为医学建议。我会先记录书名、版本与页码，再写下自己的疑问。\n\n### 想听听你的方法\n\n遇到不同版本的表述，你会怎样组织阅读笔记？');
      await writer.page.getByRole('button', { name: '预览正文', exact: true }).click();
      await expect(writer.page.getByRole('region', { name: '正文预览' }).getByRole('heading', { name: '从一次阅读开始', exact: true })).toBeVisible();
      await capture(writer.page, 'live-discussion-compose');
    });
    let discussion!: Post;
    await test.step('publishes a discussion and builds a safe body outline', async () => {
      const response = writer.page.waitForResponse(r => r.request().method() === 'POST' && new URL(r.url()).pathname === '/api/v1/posts');
      await writer.page.getByRole('button', { name: '发布讨论', exact: true }).click();
      const created = await response; expect(created.status()).toBe(201); discussion = (await created.json()).data;
      await expect(writer.page).toHaveURL(`/community/posts/${discussion.id}`);
      await expect(writer.page.locator('.reader-toc-desktop a')).toHaveCount(2);
      await writer.page.locator('.reader-toc-desktop a').last().click();
      await expect(writer.page.locator('.community-prose h3')).toBeInViewport();
      await expect(writer.page.locator('.reader-toc-desktop a').last()).toHaveAttribute('aria-current', 'location');
      expect(await writer.page.locator('.community-prose h3').evaluate(node => node.getBoundingClientRect().top)).toBeGreaterThan(await writer.page.locator('.site-header').evaluate(node => node.getBoundingClientRect().bottom));
      await capture(writer.page, 'live-discussion');
    });
    await test.step('likes, bookmarks, comments and replies persist through the original APIs', async () => {
      await reader.page.goto(`/community/posts/${discussion.id}`);
      await reader.page.getByRole('button', { name: /^点赞/ }).click();
      await expect(reader.page.getByRole('button', { name: '已点赞 1', exact: true })).toBeVisible();
      await reader.page.getByRole('button', { name: /^收藏/ }).click();
      await expect(reader.page.getByRole('button', { name: '已收藏 1', exact: true })).toBeVisible();
      await reader.page.getByLabel('写评论', { exact: true }).fill('隔离验收：我也会把版本和页码一起记录。');
      await reader.page.getByRole('button', { name: '发布评论', exact: true }).click();
      await expect(reader.page.locator('.community-comment').filter({ hasText: '隔离验收：我也会把版本和页码一起记录。' })).toBeVisible();
      await reader.page.reload();
      await expect(reader.page.getByRole('button', { name: '已点赞 1', exact: true })).toBeVisible();
      await expect(reader.page.getByRole('button', { name: '已收藏 1', exact: true })).toBeVisible();
      await writer.page.goto(`/community/posts/${discussion.id}`);
      await writer.page.locator('.community-comment').filter({ hasText: '隔离验收：我也会把版本和页码一起记录。' }).getByRole('button', { name: '回复', exact: true }).click();
      await writer.page.getByLabel('回复 隔离验收交流者', { exact: true }).fill('隔离验收回复：谢谢分享，我们可以继续对照不同版本。');
      await writer.page.getByRole('button', { name: '发布回复', exact: true }).click();
      await expect(writer.page.getByText('隔离验收回复：谢谢分享，我们可以继续对照不同版本。', { exact: true })).toBeVisible();
      await writer.page.locator('button[data-replies]').first().click();
      await expect(writer.page.getByRole('region',{name:'评论回复'})).toHaveCount(0);
      await writer.page.locator('button[data-replies]').first().click();
      await expect(writer.page.getByRole('region',{name:'评论回复'})).toBeVisible();
      await expect(writer.page.locator('.reader-comment-jump')).toContainText('2 条评论');
      await capture(writer.page, 'live-conversation');
    });
    let article!: Article;
    const title = `从阅读到记录：一次共读的整理方法 ${suffix}`;
    await test.step('saves a real article draft and submits a frozen version for review', async () => {
      await writer.page.goto(`/account/articles/new?section=${section.id}`);
      await writer.page.locator('#article-title').fill(title);
      await writer.page.locator('#article-section').selectOption(section.id);
      await writer.page.locator('#article-body').fill('## 从资料到问题\n\n这是独立数据库中的UI验收文章，不提供医疗建议。把阅读时的疑问记下来，也是一种分享。文献[1]。\n\n## 把出处留在身边\n\n此处检验段落、标题和投稿流程。');
      const references = writer.page.getByRole('region', {name:'编辑参考文献'});
      await references.getByRole('button',{name:'添加参考文献',exact:true}).click();
      await references.locator('input[id^="reference-title-"]').fill('隔离验收参考文献');
      await references.getByRole('button',{name:'添加参考文献',exact:true}).click();
      await references.locator('input[id^="reference-title-"]').fill('待删除的文献');
      await references.getByRole('button',{name:'删除文献 2',exact:true}).click();
      await expect(references.locator('.reference-toggle')).toHaveCount(1);
      await expect(writer.page.locator('#article-body')).toHaveValue(/文献\[1\]/);
      await capture(writer.page, 'live-article-compose');
      const response = writer.page.waitForResponse(r => r.request().method() === 'POST' && new URL(r.url()).pathname === '/api/v1/me/articles');
      await writer.page.getByRole('button', { name: '保存草稿（顶部）', exact: true }).click();
      const created = await response; expect(created.status()).toBe(201); article = (await created.json()).data;
      await expect(writer.page).toHaveURL(`/account/articles/${article.id}`);
      await expect(writer.page.locator('.server-draft-status')).toContainText('已保存到服务器');
      await writer.page.reload();
      await expect(writer.page.locator('#article-body')).toHaveValue(/文献\[1\]/);
      await expect(writer.page.locator('.server-draft-status')).toContainText('已保存到服务器');
      expect((await reader.context.request.get(`/api/v1/posts/${article.id}`, { headers: reader.headers })).status()).toBe(404);
      await writer.page.getByRole('button', { name: '提交审核', exact: true }).click();
      await expect(writer.page.getByText('已提交审核，审核通过后公开展示。')).toBeVisible();
      await expect(writer.page.locator('#article-body')).toHaveAttribute('readonly', '');
    });
    await test.step('a different authorized reviewer approves the submitted article', async () => {
      await reviewer.page.goto('/reviews');
      await reviewer.page.getByRole('link', { name: title, exact: true }).click();
      await reviewer.page.getByRole('button', { name: '领取审核任务', exact: true }).click();
      await reviewer.page.getByLabel('审核意见（必填）').fill('核对隔离验收样本的正文和版本，仅用于交互验收。');
      await reviewer.page.getByRole('button', { name: '确认审核结论', exact: true }).click();
      await expect(reviewer.page.getByRole('status').filter({ hasText: '审核已通过' })).toBeVisible();
      await reader.page.goto(`/community/posts/${article.id}`);
      await expect(reader.page.locator('.community-article-header h1')).toHaveText(title);
      await expect(reader.page.getByRole('region',{name:'参考文献'})).toContainText('隔离验收参考文献');
      await expect(reader.page.locator('.community-prose a[href^="#reference-"]')).toHaveCount(1);
      await expect(reader.page.locator('.reader-toc-desktop a')).toHaveCount(2);
      await capture(reader.page, 'live-reviewed-article');
    });
    await test.step('discovery retains search, type, sort, real counts and pagination', async () => {
      await reader.page.goto('/community');
      await reader.page.getByLabel('搜索社区内容').fill(String(suffix));
      await reader.page.getByRole('button', { name: '应用筛选', exact: true }).click();
      await expect(reader.page.locator('.community-post-card')).toHaveCount(2);
      await expect(reader.page.locator('.post-card-discussion')).toHaveCount(1);
      await expect(reader.page.locator('.post-card-article')).toHaveCount(1);
      await expect(reader.page.locator('.post-default-cover')).toHaveCount(0);
      await expect(reader.page.getByLabel('内容类型',{exact:true})).toHaveCount(0);
      await expect(reader.page.locator('.post-card-summary').first()).not.toContainText('##');
      await capture(reader.page, 'live-discovery');
      await reader.page.getByRole('navigation', { name: '内容发现类型' }).getByRole('button', { name: '科普文章', exact: true }).click();
      await expect(reader.page.locator('.community-post-card')).toHaveCount(1);
      await reader.page.getByLabel('搜索社区内容').fill('没有匹配结果的验收搜索');
      await reader.page.getByLabel('排序').selectOption('LATEST_REPLY');
      await expect(reader.page.getByText('没有符合条件的内容。', { exact: true })).toBeVisible();
      await reader.page.getByLabel('搜索社区内容').fill(String(suffix));
      await reader.page.getByRole('navigation', { name: '内容发现类型' }).getByRole('button', { name: '全部内容', exact: true }).click();
      await expect(reader.page.locator('.community-post-card')).toHaveCount(2);
      await expect(reader.page.getByRole('button', { name: '下一页', exact: true })).toBeDisabled();
      await expect(reader.page.locator('.community-pagination')).toContainText('共2条 · 第1/1页');
    });
    await test.step('leaves the isolated section through the existing membership rule', async()=>{ await reader.page.goto(`/community/sections/${section.id}`); await reader.page.getByRole('button',{name:'退出分区',exact:true}).click(); await expect(reader.page.getByRole('button',{name:'加入分区',exact:true})).toBeVisible(); });
    expect(errors).toEqual([]);
  } finally {
    for (const context of contexts) await context.close();
  }
});
