# 第三阶段第一批 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现已确认的社区基础，包括两级分区/成员、版本化讨论评论、文章文献/长文扩容和真实界面，并保留第二阶段全部能力。

**Architecture:** 复用 content_resources/content_revisions、身份权限、审核和资源互动；社区元数据、评论版本、政策/额度和写入控制独立分责。MySQL 是事实来源，Java 控制权限和事务，Vue 通过真实 API 展示公开版及作者稿。批次二/三能力保持明确未开放。

**Tech Stack:** JDK17、Spring Boot3.5.16、JdbcTemplate/MyBatis、Flyway/MySQL8、Vue3/TypeScript/Element Plus、Vitest/Playwright。沿用 FastAPI 解析，不增加医学模型裁决。

**Workspace:** `C:/Users/86150/.codex/worktrees/stage-three-batch-one/tcm-learning-platform`，分支 `codex/stage-three-batch-one`。原项目设计稿保留，新工作区使用独立 runtime/MySQL，不拷贝用户凭据，不修改原开发数据库。

**Design:** `docs/superpowers/specs/2026-11-20-stage-three-batch-one-data-api-design.md` 与业务设计是验收依据。若发现代码/数据库约束与设计不一致，记录证据并在不改变已确认业务规则的前提下修正技术设计。

## 任务与检查点

### Task 1：迁移、旧数据快照和约束

**Files:** 新增 `backend/src/main/java/db/migration/V7__CommunityFoundation.java`、`V8__CommunityVersionSnapshots.java`；新增 `backend/src/main/resources/db/migration/V9__community_quotas_and_writes.sql`；新增 `backend/src/test/java/cn/tcm/platform/CommunityMigrationTest.java`；更新 `IdentityIntegrationTest.java` 内旧迁移/表数断言。

- [x] 在独立 MySQL 上记录现有后端基线；建立隔离 schema 的 Flyway 迁移测试，不在测试中 clean 开发库。
- [x] 先写并运行失败测试：V6 数据升级后旧资源/评论正文和状态保留、分区规范名去重、根回复回填、评论版本复合外键、额度 pending 唯一、政策 head 单一。断言失败须由新对象缺失触发。
- [x] 实现 V7 分区层级/成员/事件和权限、旧未分类容器；V8 政策/元数据/文献/评论版本及安全回填；V9 额度/申请/receipt/限频及索引、CHECK/FK。规范化碰撞或非法回复关系在破坏性回填前拒绝。
- [x] 用夹具验证空库全迁移、V6 含数据升级、非法数据预检阻断。校验原 V1—V6 内容未改，不创建账号或密钥。
- [x] 运行 `./scripts/test-backend.ps1 -TestPattern 'CommunityMigrationTest,IdentityIntegrationTest'`，保存 `runtime/stage-three-task1-*.log`，规格复核后再做质量复核，修复后重复对应验证。

### Task 2：文本策略、正文/引用验证、幂等与限流

**Files:** 新增 `community/application/CommunityTextPolicy.java`、`CommunityTextValidator.java`、`CommunityWriteGuard.java`、`community/infrastructure/CommunityRequestLimitFilter.java`、对应 DTO/政策控制器；修改 `SecurityConfiguration.java` 和 `GlobalExceptionHandler.java`。新增 `CommunityTextPolicyTest.java`、`CommunityWriteGuardIntegrationTest.java`、`CommunityRequestLimitTest.java`。

- [x] 先写失败用例：中文/补充平面表情字符计数，保留 Markdown 缩进，协议/禁止语法，版本化政策和越权激活，同 key 不同载荷、分钟/小时窗口、413/429 与 Retry-After。
- [x] 实现独立政策读取/版本创建/head 乐观激活；固定 schema 和禁止能力校验，不能通过政策 JSON 开启 HTML/脚本/图片。公开政策不含内部治理规则。
- [x] 实现正文计数与安全语法、文献稳定键/编号校验及源文摘要；文献 URL/DOI 不联网；源文差异是事实依据。草稿引用未完成与提交阻断分别处理。
- [x] 实现事务内 receipt、账号/操作窗口及确定锁顺序；重放需重新鉴权并安全读取结果，不保留长正文；同键重试不重复耗额。
- [x] 加入实际请求体限量读取、压缩请求拒绝或有界处理、CORS 幂等头和 Retry-After；错误沿用 envelope。
- [x] 运行 `./scripts/test-backend.ps1 -TestPattern 'CommunityTextPolicyTest,CommunityWriteGuardIntegrationTest,CommunityRequestLimitTest'`，保存日志并完成两轮审查。

### Task 3：分区、成员、讨论与评论真实 API

**Files:** 新增 `community/api/CommunityDtos.java`、`CommunityController.java`、`CommunityAdminController.java`，新增 `community/application/CommunitySectionService.java`、`CommunityPostService.java`、`CommunityCommentService.java`，新增对应聚焦 SQL 仓储。修改 `IdentityService.java`；新增 `CommunityWorkflowIntegrationTest.java`、`CommunityConcurrencyTest.java`。

- [x] 先验证缺口：登录用户读取分区、加入退出、普通用户直接发讨论、同帖同串评论与编辑冻结、管理员两级配置/负责人边界及禁用关系失效。
- [x] 实现两级与同父名称约束、独立系统管理权限、负责人简介/规则与本区置顶；隐藏历史容器不出普通导航，不开放用户自建入口。
- [x] 实现本人关系幂等入退；禁用账号同事务撤销加入/负责人权限，解禁不恢复；未实现交接时负责人退出明确409。
- [x] 实现公开列表/详情、作者历史及源文本差异；讨论生成新版本、DIRECT 发布、旧版本不可改；复用现有资源互动/历史，不复制计数。
- [x] 实现两层评论、指定回复对象、根/回复独立分页、删除占位；隐藏/删除目标不能再接新回复，已有公开回复保留；公开选择使用正式指针。
- [x] 真实并发验证加入/禁用、同稿双写、发布/只读、幂等重试；验证全写入 rollback 不产生半份元数据。运行 `./scripts/test-backend.ps1 -TestPattern 'CommunityWorkflowIntegrationTest,CommunityConcurrencyTest,PersonalCenterIntegrationTest'` 并完成两轮审查。

### Task 4：文章文献、动态额度与现有审核兼容

**Files:** 新增 `community/application/CommunityArticleService.java`、`CommunityReferenceService.java`、`CommunityQuotaService.java`、对应控制器/DTO/仓储；修改 `PersonalCenterDtos.java`、`PersonalCenterController.java`、`PersonalCenterService.java`、`ReviewService.java`；新增 `CommunityArticleIntegrationTest.java`、`CommunityQuotaIntegrationTest.java`。

- [x] 先复现旧 DTO 超过10万字符必拒、文献无法保存、审批接口缺失、治理隐藏被旧审核恢复等缺口；测试保存源文/文献和公开分区的版本隔离。
- [x] 新旧个人中心路径统一代理同一文章服务；创建支持兼容旧 title/body，更新必须锁版本；省略 references 保留、空数组显式清空；未分类不能提交公开。
- [x] 文献独立额度、冻结/复制和编号关联原子保存，代码中的 `[1]` 不当引用改写；重排用已保存DRAFT＋稳定键排列的独立命令，返回同步改写的源文和新锁，普通保存不暗改现有编号；原意见/原公开版不能被文献编辑影响。
- [x] 扩容本人申请、管理员非自批、同文章唯一 pending；批准额度/申请/审计/通知原子提交；新旧编辑/提交入口一致检查，审批不发布不解封。
- [x] 首次长文无ID时提供标题级私有draft-identities命令；空正文不得提交，编辑器不能丢弃或静默截断全文；批准额度后仍通过常规保存校验。
- [x] 审核文章锁序先账号/分区后资源/任务，重核治理状态；保留原来源/知识发布锁序与旧正式版；审核页读取冻结文献。
- [x] 运行 `./scripts/test-backend.ps1 -TestPattern 'CommunityArticleIntegrationTest,CommunityQuotaIntegrationTest,PersonalCenterIntegrationTest,KnowledgeWorkflowIntegrationTest,SourceWorkflowIntegrationTest'`，完成规格和质量审查。

### Task 5：社区界面及管理入口

**Files:** 新增 `frontend/src/modules/community/{api.ts,types.ts,CommunityListView.vue,CommunitySectionView.vue,CommunityDetailView.vue,DiscussionEditorView.vue,CommentThread.vue,CommunityMarkdown.vue,ReferenceEditor.vue,QuotaPanel.vue,CommunitySectionsAdminView.vue,CommunityQuotasAdminView.vue,CommunityPoliciesAdminView.vue,community.css}`；修改 router、PortalLayout、AdminLayout、ArticleEditorView、ResourceView、shared/api/types。新增对应真实行为组件测试。

- [x] 读取既有 CSS/布局/表单和 frontend-design 指引；沿用深绿、字体和组件，不重做项目视觉系统。
- [x] 测试先行覆盖旧请求晚到、切换文章不串写、完整 source 保存、长文额度、稳定文献编号、两层评论/占位、429保留输入、只读/无权限/空/错状态。
- [x] 先提供 typed API 和复用编辑/渲染/文献/额度组件，再实现列表、分区、详情评论和讨论发布；采用安全渲染器且禁用原始HTML/图片，评论只用文本节点。
- [x] 文章编辑升级动态额度、分区、版本锁和文献；公开/作者稿不混用；兼容原路由和已发布文章阅读。只保存私有草稿，不因输入自动公开。
- [x] 增加明确权限保护的分区、额度和政策管理页；不显示后续未实现的自建/申诉/专业复用为可用功能。
- [x] 运行 `npm test`、`npm run build`；验证组件与真实后端契约一致，完成规格及质量审查。

### Task 6：端到端、并发补验、交付记录

**Files:** 新增 `frontend/e2e/stage-three-batch-one.spec.ts`；按需扩展角色准备脚本；新增 `docs/11-第三阶段第一批实现与验收.md`；更新 README、contracts、阶段状态和规划记录。

- [x] 在新工作区独立 MySQL/端口启动三端；依赖就绪后走真实浏览器，不以 mock 代替端到端。隔离账户/合成内容，不使用现有用户凭据。
- [x] 新业务链：分区→加入→普通讨论→评论/回复/删除占位→点赞收藏→文章文献→扩容审批→稿件审核→公开与修订隔离；验证无权限/跨串和版本冲突可见反馈。
- [x] 全量后端、前端、生产构建、未改动 AI 服务回归及原12项浏览器；375/1440截图人工检查无横向溢出、弹窗裁切、焦点和安全占位。
- [x] 最终独立规格审查通过后做质量审查；修复问题并运行相关回归，不用旧日志宣称新验证。
- [x] 以实际报告记录新增表/迁移数、测试数、真实浏览器覆盖和未实施边界。更新 task_plan/progress/findings；使用 finishing-a-development-branch 收敛交付。按用户后续明确授权，验收后以中文提交合入并推送远程 main；保留 main 学习注释，重复方法完整备份而不合入，不推送 codex 分支。

## 执行约定

- 子任务逐个实现，修改范围明确；规格审查通过后才进入质量审查，问题修复后复核。仅协调者发起测试/启动/集成，避免同时写同一数据库或运行态。
- 基线日志与每次 RED/GREEN、构建、E2E 日志保存在 runtime；公开文档不含口令/token。测试失败先定位，不清空现有开发库，不跳过失败用例。
- 本计划实施范围只覆盖第一批；第二/三批虽已设计，不冒充已交付。用户已授权按流程连续推进，不因常规技术选择反复请求确认。
