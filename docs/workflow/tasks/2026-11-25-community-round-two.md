# 科普社区完整流程第二轮优化（2026-11-25）

## 范围与结论

保留第二版全站视觉基准，改善社区发现、创作、阅读与互动。无外部设计调研、数据库结构/API/依赖/认证/审核规则更改，不提交推送。原5173/8080正常复用；写入验收复用独立 runtime/community-ux-acceptance 数据库及5175/8082/8002，未改写、删除或清理原站内容与账户。

## 缺陷查证与修复

1. 导航：真实Edge在0、562/600px滚动位置均测得header top=0，父容器无异常transform/overflow。滚动后直接全页截图可使sticky导航出现在长图中部，属于捕获时的滚动位置影响；截图统一先回顶部，未提高z-index。补充ResizeObserver测量实际导航高度，锚点/表单焦点按高度避让；Skip Link改用自身高度平移隐藏，聚焦才显示，进入main时关闭展开菜单。最终浏览器验证焦点和Skip Link跳转未被导航遮挡。
2. 摘要：后端CommunityPostRepository使用LEFT(body,180)，提供的是源文本截断。当前代码已有communityExcerpt，能够按sourceFormat提取Markdown纯文本，详情也已经没有重复摘要框；本轮直接保留，新增标题、引用、列表、代码块回归，不伪称重写了解析器，也不把历史PLAIN_TEXT改成Markdown。预览改为直接复用CommunityMarkdown/DOMPurify，自动更新并遵循实际排版策略。原正文不修改。
3. 目录：短正文无法将末节滚到视口顶部时，滚动监听会覆盖点击后的高亮。保留点击目标直到实际滚动位置变化，并对重复/特殊标题使用现有唯一ID；点击同时定位并聚焦，加入aria-current及短文回归。
4. 筛选重试：第2页切换新类型请求失败后，原重试会取旧列表页码；新增requestedPage明确记录本次请求页码，重试使用新筛选的第1页。已先用测试复现，再修复。

## 实际完成

- 三栏社区结构保留，压缩顶部欢迎区；右栏移除重复宣传与分区列表，仅保留社区规范和明确标注“来自当前列表”的真实分享者。
- 内容类型仅保留Tab；搜索支持Enter；排序/包含子分区变化自动提交并重置分页；保留latestRequest旧响应隔离与加载/空/失败/重试状态。
- 文章/讨论使用不同类型标签与背景，移除植物占位封面。现有Post DTO无封面字段，不猜测正文图片；摘要保持三行。作者、时间、分区、真实计数与可点击评论统一呈现，历史内容标识保留。
- 新建MarkdownEditor：标题、粗体、斜体、有序/无序列表、引用、链接、代码块，选区和光标操作；代码围栏避开选中文本中的反引号，并在段落内插入时建立独立代码块。编辑/分屏/完整预览，桌面分屏、手机切换；策略未开放时正确禁用。
- SourcePreview从手动快照改为自动使用当前正文、格式和文献；正式详情与预览共享安全渲染。图片和原始HTML保持现有安全规则。
- 讨论补充LocalStorage草稿：账号+POST+编辑资源隔离，白名单只存标题/正文/分区/修改说明，显式恢复或放弃，成功发布清除；存储禁用/失败时提示并保留编辑内容；不存令牌。文章已有服务器草稿，继续保存/冻结/审核/修订/扩容流程，不增设第二套文章本地保存。原离开确认和beforeunload提醒保留。
- 创作帮助折叠并移到紧凑区域，标题强化，必填与实际字数/额度可读；发布忙碌状态、失败输入保留与旧请求/并发版本保护保留。
- 阅读标题/作者与正文统一最大宽度；文章保留宋体阅读，讨论突出发起者/问题/评论入口。无标题不展示目录，文献和分区规则只展示实际数据。列表与详情复用CommunityGlyph和互动样式，不改互动接口或权限。
- 增强焦点、正文/辅助文字、代码表格局部滚动、平板栏下移与手机单列。

## 涉及文件清单

| 作用 | 本轮文件 |
|---|---|
| 必要公共导航 | frontend/src/app/PortalLayout.vue、styles.css |
| 发现与卡片 | frontend/src/modules/community/PostFeed.vue、CommunityCompanion.vue、CommunityPostCard.vue |
| 创作页面 | frontend/src/modules/community/DiscussionEditorView.vue、ComposeGuide.vue、SourcePreview.vue；frontend/src/modules/account/ArticleEditorView.vue |
| 新增复用组件/工具 | frontend/src/modules/community/MarkdownEditor.vue、CommunityGlyph.vue、markdownEditing.ts、useDiscussionDraft.ts |
| 阅读与样式 | frontend/src/modules/community/CommunityOutline.vue、CommunityReader.vue、community-experience.css |
| 回归 | frontend/src/modules/community/{MarkdownEditor,CommunityOutline,markdownEditing,useDiscussionDraft}.test.ts、communityUi.test.ts；frontend/e2e/{community-experience,stage-three-batch-one}.spec.ts |
| 入口与交接 | MODULE_INDEX.md、docs/modules/{community,account}.md、DEVELOPMENT_STATE.md及本记录 |

CommunityMarkdown、reading.ts、CommentThread、SectionPanel、UserAvatar、审核/额度/文献组件直接复用，没有把开始前的修改算成本轮交付。基线副本位于runtime/community-round-two/community-before及*.before.*，按副本定向审查。新增社区路径已有全前端选择规则，自动覆盖账户编辑器消费者，无需更改Test-Policy。

## 本次测试与证据

- test-changed.ps1 -PlanOnly退出0，runtime/community-round-two/test-plan.log。工作区有多项既有改动；本轮审查确认前端范围，因此执行全前端，不运行未触达的后端/AI套件。
- 最终test-module.ps1 -Module all -Layer frontend：25文件137项单元/组件测试通过，vue-tsc/Vite通过；框架证据runtime/workflow/20261125-103141-888-317d4b97/{plan,result}.json。后续仅修正工具栏段落内代码块和空首行边界，再执行markdownEditing/MarkdownEditor 8项专项通过及最终类型/构建，日志final-tool-tests.log、final-source-build.log。不把较早构建失败记作通过。
- 既有community-experience.spec.ts在独立库真实执行：1条完整E2E、7个业务步骤通过（e2e-second.log）。覆盖真实加入分区→讨论草稿刷新恢复→工具栏/分屏/预览→发布→目录→点赞/收藏持久化→评论/回复→文章服务器草稿→冻结提交→另一审核者审核公开→筛选→退出分区。stage-three-batch-one旧下拉框选择器同步为Tab；未重复执行整个第三阶段套件。
- browser-check.cjs：35项通过，覆盖5类页面×4尺寸、导航/Skip Link、本地草稿放弃、存储禁用、空/503列表×4尺寸、长标题/长正文/重复标题/表格/代码块×4尺寸，pageerror=0。
- supplement.cjs：18项最终补验通过，覆盖创作和两类详情×4尺寸、Enter搜索/即时排序、发布失败保留输入及阻止重复提交，pageerror=0。
- 视口：1440×900、1280×800、768×1024（部分状态900高）、390×844（部分状态900高）；无页面横向溢出。全页截图前归零滚动，避免sticky捕获伪影。
- 项目未配置独立lint/typecheck命令；类型检查由build中的vue-tsc --noEmit完成。未安装lint或新增依赖。

失败记录保留：首次新工具测试缺实现；目录E2E暴露短文高亮缺陷后修复；新测试的SourceFormat被TS拓宽导致构建失败，明确字面量类型后通过；预览态断言错误寻找已隐藏工具栏，以及浏览器补验精确匹配“排序”标签失败，修正测试定位后通过。相关日志在runtime，不计作成功。最后分页重试回归先失败后通过。

## 截图

截图目录runtime/community-round-two/，主页面截图来自独立环境真实发布/审核后的内容；long-article-*、list-empty/error-*及失败请求仅为浏览器响应夹具，未写入数据库。

| 页面 | 1440 | 1280 | 768 | 390 |
|---|---|---|---|---|
| 社区首页 | [截图](../../../runtime/community-round-two/community-1440.png) | [截图](../../../runtime/community-round-two/community-1280.png) | [截图](../../../runtime/community-round-two/community-768.png) | [截图](../../../runtime/community-round-two/community-390.png) |
| 发布讨论 | [截图](../../../runtime/community-round-two/discussion-editor-1440.png) | [截图](../../../runtime/community-round-two/discussion-editor-1280.png) | [截图](../../../runtime/community-round-two/discussion-editor-768.png) | [截图](../../../runtime/community-round-two/discussion-editor-390.png) |
| 撰写文章 | [截图](../../../runtime/community-round-two/article-editor-1440.png) | [截图](../../../runtime/community-round-two/article-editor-1280.png) | [截图](../../../runtime/community-round-two/article-editor-768.png) | [截图](../../../runtime/community-round-two/article-editor-390.png) |
| 文章详情 | [截图](../../../runtime/community-round-two/article-detail-1440.png) | [截图](../../../runtime/community-round-two/article-detail-1280.png) | [截图](../../../runtime/community-round-two/article-detail-768.png) | [截图](../../../runtime/community-round-two/article-detail-390.png) |
| 讨论详情 | [截图](../../../runtime/community-round-two/discussion-detail-1440.png) | [截图](../../../runtime/community-round-two/discussion-detail-1280.png) | [截图](../../../runtime/community-round-two/discussion-detail-768.png) | [截图](../../../runtime/community-round-two/discussion-detail-390.png) |

## 未开放与验证边界

- 删除线：当前服务端Syntax/排版策略不支持，工具栏明确禁用并提示，未绕过策略。
- 图片上传/真实封面：现有正文策略禁图片、DTO无封面，没有构建虚假上传或图片按钮。
- 文章沿服务器草稿；讨论本地草稿只保证当前浏览器可用，不宣称跨设备同步。
- 无新的精选/排行榜接口，不制造右栏数据。未验证生产部署、整个阶段三后续治理、后端/AI全量测试；本轮未改其代码。
