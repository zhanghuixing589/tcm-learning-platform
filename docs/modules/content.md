# 公共内容、版本与审核

2026-11-27验收更新：来源覆盖确认、知识审核、独立提案审核及通知跳转已通过专项；本人回避、角色门禁、版权复查与冻结机制保留。当前交付及分次测试证据见[验收](../workflow/tasks/2026-11-26-source-reading/acceptance.md)。

来源阅读V2集成（2026-11-26）：ReviewService新增KNOWLEDGE_PROPOSAL任务，绑定独立提案而非伪造正式revision；来源审核记录冻结阅读版本、警告确认、可靠文字批准覆盖和REFERENCE_ONLY/TEXT_CITABLE用途。提交和批准都复查当前版权/来源有效性，正式发布与RAG索引许可分离；未许可或无可靠锚点不建索引任务。权限/本人回避/领取/乐观锁保留，新增专项KnowledgeProposalIntegrationTest/SourceReadingIntegrationTest及共享回归策略。准确测试范围见DEVELOPMENT_STATE。

V10分类补充（2026-11-25）：知识稿件目录必须属于其固定类型，标签仍通过revision_tags跨类型关联并绑定版本。KnowledgeService.publicationReady在原提交/审核编排中检查目录及标签有效性并取得共享锁；管理员停用后待审稿需退回修订，已发布阅读/收藏不受影响。旧目录归档不改冻结版本引用。契约见contracts/knowledge-taxonomy.md，迁移及回归新增KnowledgeTaxonomyIntegrationTest/KnowledgeTaxonomyMigrationTest。

状态：通用资源版本、来源/知识/基础文章审核已实现；第三阶段专业意见复用与治理增强仍是方案。依据：第二阶段 spec、第三阶段 spec 和 `docs/03-数据库关系与业务规则.md` 对应内容规则。

## 结构与数据

content 包的 ContentSupport 提供通用数据和权限支持，ReviewController / ReviewService 编排审核；知识写入在 KnowledgeService，文章写入在 PersonalCenterService，来源流程在 SourceService。前端 ReviewView / ReviewsView / FeedbackQueue 均位于 knowledge 目录。

content_resources 是稳定资源身份，content_revisions 保存版本；revision_evidence 绑定来源依据；review_tasks / review_events 保存审核任务和历史。引用、公开指针与状态不能仅按页面所在目录归属。

## 状态和权限

编辑草稿 → 提交冻结 → 待审任务 → 领取/决策 → 发布或退回；领取/决策携带 lockVersion，过时版本应冲突。既有正式版与待审修改隔离，不以修订替换原公开版。精确枚举读取 ReviewDtos / ReviewService 和提交方实现。

ReviewService 按任务对象区分 knowledge:review 和 community:moderate，并禁止本人审核。不能从 ADMIN 角色推导医学审核权。公开知识还需要有效来源/证据，审核通过不等于后续检索索引就绪。

## 契约与回归

独立修订提案的审核结果通知跳转`/contribute/proposals/:proposalId`，沿用resource_id关联正式知识；不要求提案贡献者拥有原知识编辑权限。回归由SourceReadingIntegrationTest的真实提交/审核/通知断言覆盖。

`/reviews` 及任务详情、领取、决策，精确路由见 ReviewController；资源修订、文章提交和知识草稿并非都由 ReviewController 提供。维护契约时必须同时核对三个消费者。

KnowledgeWorkflowIntegrationTest、SourceWorkflowIntegrationTest、PersonalCenterIntegrationTest，前端 ReviewView.test.ts / FeedbackQueue.test.ts。重点验证自审、越权、版本冲突、重复决策、退回重提、来源失效和旧公开版保留。任何通用审核修改都进入共享回归组。
