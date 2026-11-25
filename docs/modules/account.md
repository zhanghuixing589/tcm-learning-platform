# 个人中心与既有文章

2026-11-25第三轮：ArticleEditorView增加顶部保存入口与draftSaveState/Label，复用原save与上下文版本保护；saveBar测量实际高度供Markdown工具栏避让。未增加本地文章草稿或直接发布。ReferenceEditor沿原服务器字段、文献编号和审核引用，新增删除提醒。见[社区第三轮交付](../workflow/tasks/2026-11-25-community-round-three.md)。

2026-11-25：ArticleEditorView正文改用community/MarkdownEditor，复用策略与安全渲染，提供实时预览/分屏和选区工具栏。继续使用现有服务器草稿、额度、文献、版本锁、审核冻结与离开提醒，不新增文章本地草稿或改API。见 [社区第二轮交付](../workflow/tasks/2026-11-25-community-round-two.md)。

状态：已实现个人中心和基础文章流程；完整第三阶段社区尚未实现。既有详细设计：`docs/superpowers/specs/2026-11-18-personal-center-design.md`；验收快照：`docs/09-个人中心改版与验证.md`。

## 结构与数据

PersonalCenterController → PersonalCenterService / AvatarService；前端 AccountView、AccountSettingsView、PublicProfileView、ResourceView、ArticleEditorView、personalCenter.ts。前端 account 目录也包含身份页面，不把整个目录视为同一接口边界。

个人关系：user_follows（V4）、resource_bookmarks、resource_likes、user_resource_history；内容复用 content_resources/content_revisions 和 community_post_profiles。公开资料与本人收藏/历史分开；数据库返回 ID 按前端类型字符串处理。

## 流程与约束

用户概要/关注 → 本人列表或公开主页 → 资源详情/互动。文章在 `/me/articles` 创建和修改草稿，通过 submit/withdraw/revise 流程与通用审核关联，修订不直接覆盖原公开版本。具体可编辑状态由服务条件判定，不把第三阶段配额/文献设计当作当前能力。

收藏/点赞重复调用不重复计数；私有列表须由当前主体查询。知识资源能否显示依赖公共 PublishedKnowledge 和来源有效性，不能仅检查 resource.status；修改知识/来源时需回归个人列表。头像只经受控上传入口读取，不暴露本地存储路径。

## 接口与验证

入口：`/me/profile-summary`、`/users/{userId}/profile`、`/users/{userId}/follow`、`/me/{bookmarks|history|publications|likes|received-likes|following|followers}`、`/resources/{resourceId}`、`/me/articles`、`/me/avatar`。精确 DTO 在 account/api/。

测试 PersonalCenterIntegrationTest、personalCenter.test.ts、ArticleEditorView.test.ts；浏览器 personal-center.spec.ts。跨用户隐私、公开/待审版本、重复互动、来源停用及历史清理是主要回归点。标准测试脚本将 account/source/content/knowledge 合并选择，避免共享表规则遗漏。

## 2026-11-24 视觉入口补充

HomeView 改为编辑式首页，HomeContent 只在登录后用现有知识/社区列表接口读取公开预览；独立失败不互相遮蔽，账号变化与卸载沿 latestRequest 丢弃旧响应。新增 HomeContent.test.ts，已有account测试选择自动覆盖。个人中心仍是名片、收藏/历史/发表/点赞列表与辅助栏，不新增个人文档知识库接口；新样式在 home.css/account.css，公共图形与状态在 app/ui。所有业务、认证和原账户保持不变。

## 2026-11-25 首页第二轮优化

第二版已正式确认为全站视觉基准。细节定稿仅加强搜索主次层级、移动端辅助文字字号，并在 HomeContent.postPreviews 复用 communityExcerpt（Markdown 转纯文本、历史纯文本原样显示）；作者复用 UserAvatar，互动文案与评论入口沿社区。四个整区学习入口、空状态、知识卡片及紧凑预告已满足要求，保留原实现。验证见 docs/workflow/tasks/2026-11-25-home-polish.md。

保留第一轮本草风格，首页增加真实知识搜索和全区域可点击的学习入口。中医概念使用现有 CONCEPT，不新增基础理论分类。贡献 CTA 使用现有 contribution:submit，游客先登录、普通学习者查看资格、可提交用户进入工作台；资格状态由原 ApplicationsView 处理。

HomeContent 的知识与社区独立请求/重试，知识为空或只有一项时不与社区等高分栏；摘要列表无封面或来源名称字段，采用无图卡片并链接知识详情来源依据，不请求会记录浏览历史的知识详情来拼装首页。社区展示真实摘要、分区、作者及评论/点赞/收藏。新增 HomeView.test.ts，HomeContent.test.ts 增补加载隔离、重试隔离与换账户旧响应回归。无 API/角色/路由契约更改。
