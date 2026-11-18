# 个人中心 Implementation Plan

> **For agentic workers:** Use subagent-driven-development for the backend task and independent frontend implementation; perform spec review before quality review. Preserve existing uncommitted changes. Do not commit unrelated work.

**Goal:** 实现已确认的个人名片、收藏/历史/发表/点赞、互动统计、关注与粉丝，以及个人设置和最小公开主页。

**Architecture:** 沿用 Spring Boot/MyBatis/MySQL，以 account 功能模块提供本人私有查询和用户公开查询。Vue 组件使用现有认证客户端，URL 保存列表状态；无业务数据时显示真实空态。身份与安全逻辑保持现有工作区版本。

**Tech Stack:** Java 17, Spring Boot 3.5, MySQL/Flyway, Vue 3, TypeScript, Element Plus, Vitest, Playwright。

## Task 1: Backend account contract and integration coverage

Files: create account API/service/query classes and `PersonalCenterIntegrationTest.java`; add V4 follow migration, preserving V3. Reuse existing identity principals and error envelopes.

- [x] Write MockMvc tests for zero statistics, bookmark/history visibility, received likes excluding self, deduplicated publications, idempotent follow, private data isolation and disabled accounts.
- [x] Run the focused Maven test and observe missing endpoint failures.
- [x] Implement summary, paginated private collections, public profile/publications, relationship mutation and resource detail/bookmark/like/history endpoints.
- [x] Provide typed contract to frontend before implementation completes. Use string IDs and ISO UTC timestamps; page defaults 1/20.
- [x] Include profile avatar upload with bounded image validation and owned storage serving, or document and resolve existing infrastructure constraints during implementation.
- [x] Run focused and existing backend tests against the isolated test database.

## Task 2: Profile shell, collection lists and public profile

Files: `frontend/src/modules/account/AccountView.vue`, new `ProfileHero.vue`, `AccountContent.vue`, `AccountSettingsView.vue`, `PublicProfileView.vue`, `ResourceView.vue`, `personalCenter.ts`, page styles, router entries.

- [x] Write meaningful frontend regression tests for stale request handling, URL filters, unavailable resources and privacy-sensitive presentation; run and verify failure.
- [x] Build the approved shallow green profile nameplate, three compact interactive counts, four content tabs and quiet right rail.
- [x] Wire actual APIs, loading/error/empty states, pagination, search, type/status filters, cancel/undo actions, follow buttons and contribution status.
- [x] Move existing profile edit to `/account/settings`; preserve save/logout and warn on unsaved changes. Add avatar update through backend API.
- [x] Add minimum public profile and safe resource detail routes. Do not render raw untrusted HTML.
- [x] Add contribution-authorized article draft editing and revision/status actions supported by backend; do not expose unsupported controls.
- [x] Verify `npm test` and `npm run build`.

## Task 3: Browser flow verification and review

Files: adapt `frontend/e2e/account.spec.ts` for settings route; add personal center browser coverage and test-only fixtures; add verification evidence document.

- [x] Verify new profile identity, zero state, settings persistence, private list switching, filters/back navigation, relationship counts and resource interactions.
- [x] Verify a long nickname, dense content list, disabled/unavailable content and failure/retry without replacing counts with zero.
- [x] Inspect real screenshots at 1440 and 390px; test overflow at 375/390/720/768/1024/1440 CSS px, including the 720px usable-width equivalent of a 1440px display at 200% zoom.
- [x] Run full backend tests, frontend tests/build and account E2E after all changes settle.
- [x] Perform independent spec review, then quality/security review; fix actionable findings and re-run affected checks.
- [x] Update design status and validation notes with exact passed checks and remaining business-module boundaries. Leave changes reviewable in the shared checkout.

## Contract defaults

`GET /me/profile-summary` -> `{ profile, receivedLikes, followingCount, followerCount }`.
`GET /me/{bookmarks,history,publications,likes,received-likes}` -> standard PageResult with item string `id` resource ID, `resourceType`, `title`, `summary`, `available`, `occurredAt`, owner identity and state fields.
`GET /me/{following,followers}` -> PageResult public user rows with `following` and `followsYou`.
`GET /users/:id/profile` -> same public profile/summary plus viewer relationship, never login username/permissions.
`GET /users/:id/publications` -> public articles only.
`PUT|DELETE /users/:id/follow` -> idempotent mutation.
`GET /resources/:id` -> allowed published resource; record last view for signed-in viewer.
`PUT|DELETE /resources/:id/bookmark` and `/like` -> idempotent mutation.

Final field names are agreed with the backend implementer before wiring; the approved spec remains the behavior authority.

## Completion evidence

2026-11-18: backend78/78 plus package, frontend42/42 plus typecheck/production build, browser10/10. Independent spec and quality reviews completed; findings fixed and re-reviewed. Live services restored at http://127.0.0.1:5173 with backend readiness UP. Detailed scope and evidence: docs/09-个人中心改版与验证.md. No commits or unrelated-file resets.
