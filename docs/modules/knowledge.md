# 知识中心、药材与方剂

第二版验收更新（2026-11-27）：六类型真实草稿、跨资料多引文、手机选文直接整理、知识审核、他人独立提案审核发布及固定版本引用定位已通过浏览器验收；新建提案保存跳转后busy释放与审核通知提案路径已修复。准确命令及分次结果见[交付验收](../workflow/tasks/2026-11-26-source-reading/acceptance.md)。

来源阅读V2集成（2026-11-26）：KnowledgeReading提供真实条目匹配、个人摘录追加与授权引文预览；KnowledgeProposals保存独立提案，所有贡献者可建议修订，审核前不改原作者草稿/正式资源，批准时检查当前正式基线及作者是否已有待审/草稿，并保留资源owner。KnowledgeDtos.Evidence补充parseRunId/readingEditionId/locator/quoteHash/excerptId，服务端按规范原文重建引用；新增草稿更新expectedVersion，复制修订保留历史锚点。既有六类型结构化字段、目录/标签规则继续复用。实际通过与未验证范围见DEVELOPMENT_STATE。

## 当前分类体系（V10，2026-11-25）

用户授权本轮完整业务重构，取代下方视觉任务“不改后端”的历史限制。固定六类型 + 类型专属动态目录 + 跨类型通用标签。KnowledgeTaxonomy / KnowledgeTaxonomyController维护目录、标签、权限和版本锁；复用content_categories/content_tags/revision_tags。公开目录按type过滤启用且非归档项；写入和筛选拒绝跨类型、停用或历史目录。KnowledgeService提交/审核批准复查有效性，标签随草稿保存与修订复制，tagId及标签名称可检索。来源/正式版本/收藏原规则不变。

V10归档旧知识目录，保留ID/名称与旧版本分类引用、正文和标签关联，不猜测医学子类；新修订不继承不可选目录。数据库类型CHECK、同类型名称唯一、索引、新赋值触发器和目录身份不可变；目录/标签lock_version及knowledge:taxonomy:manage权限，只有ADMIN默认获得，不含知识审核权限。

KnowledgeListView改为标题下横向目录，切类型清目录/分页、全部隐藏；通用标签跨类型保留。KnowledgeEditorView只能选当前类型目录并维护标签；VersionCompare/KnowledgeRead呈现标签及差异。KnowledgeTaxonomyAdminView、taxonomyApi用于/admin/knowledge/categories，停用保留历史，无删除。

API和兼容见[分类契约](../../contracts/knowledge-taxonomy.md)。新增KnowledgeTaxonomyIntegrationTest / KnowledgeTaxonomyMigrationTest、KnowledgeTaxonomyAdminView.test / KnowledgeEditorView.test，增补目录回归；真实frontend/e2e/knowledge-taxonomy.spec.ts只在显式隔离环境启用。测试策略纳入knowledge/content共享回归簇。本轮记录见docs/workflow/tasks/2026-11-25-knowledge-taxonomy.md。

状态：第二阶段知识贡献、审核发布、浏览、纠错及方剂比较已实现；RAG/向量/图谱仍是后续阶段。详细依据：第二阶段 spec 与 `docs/10-第二阶段实现与验收.md`。

## 实现与数据

KnowledgeController / KnowledgeDtos → KnowledgeService，复用 ContentSupport 与 common/PublishedKnowledge。前端 modules/knowledge/api.ts 定义接口/类型，KnowledgeListView、KnowledgeDetailView、KnowledgeEditorView、ImportView、FormulaCompareView 为主要入口。

knowledge_entries / knowledge_aliases 保存正式名称；V5 的 knowledge_revision_names / knowledge_revision_aliases 保存候选版本名称，不允许草稿覆盖正式名。herb_profiles、formula_profiles、formula_ingredients、knowledge_relations 绑定结构化事实；公共版本和证据仍在 content_revisions / revision_evidence。

## 业务链与不变量

贡献者创建/编辑草稿 → 附加可追溯依据及结构化事实 → 提交冻结 → 专业审核 → 发布正式指针 → 知识中心读取。修订、新候选名称与旧公开内容隔离；发布事务创建待处理索引任务，但当前没有实际 RAG 检索能力。

已发布读取必须检查版本和来源有效性，影响收藏、历史、通用资源详情等消费者。纠错不能借详情获取重复增加浏览计数；方剂解析/匹配/比较的输出不作为诊断或医学正确概率。

## 接口与验证

`/knowledge/categories`、`/knowledge`、`/contributions/knowledge`、`/knowledge/{id}` 下 relations/draft/revise 等，以及 `/formulas/*`；精确操作和输入由 KnowledgeController、KnowledgeDtos、前端 api.ts 交叉核对，不以旧契约总表代替。

KnowledgeWorkflowIntegrationTest、editor.test.ts、KnowledgeDetailView.test.ts；联动 SourceWorkflowIntegrationTest、PersonalCenterIntegrationTest 和审核组件测试。验证草稿隔离、审核/发布、别名版本、依据失效、公开详情及纠错副作用；真实链路按 stage-two.spec.ts 选择。

## 2026-11-24 视觉入口补充

KnowledgeListView 保留查询参数/分类/分页，增加类型索引与记录卡。KnowledgeDetailView 用知识正文与阅读导航两栏组合；KnowledgeRead 增加可选 anchorPrefix，仅正式详情传入，审核/版本比较不生成重复页面锚点。贡献工作台、审核与任务复用 StatusBadge，状态值和操作条件不变。样式仍由 stage.css 管理，无API、表结构或审核规则变动。

## 2026-11-25 数字本草图书馆

- 目录改为突出搜索、单套六类型导航、后台真实共享分类与分页。KnowledgeCatalogCard 按类型展示药材属性、方剂组成/出处、概念定义/关联、功效/症状/经络轻量条目；library.css 只覆盖本次页面。无图片字段时不生成鉴别图。
- KnowledgeCatalogEntry 补充现有搜索响应已含的可选结构字段；不改服务端契约、不逐条请求详情增加浏览记录。当前分类只有 id/name/code，无类型映射或层级；排序由后端固定为最近更新。
- formulaShelf 是账号隔离的内存选择，最多3项，跨目录/详情/比较及搜索分页保留，刷新清空；账号变化同步清除。FormulaSelectionBar 共用选择/移除/清空反馈。比较仍请求原 /formulas/compare，依据真实药材 resource ID 标识共同/差异组成，保留原始角色、用量、依据索引。
- 正式详情保留收藏、版本对照、纠错、审核者停用；按实际章节生成导航，异步载入后处理来源锚点，403/404统一为不泄露存在性的不可访问提示。正文仍按现有纯文本格式安全展示，不擅自解释成 Markdown。
- 新回归：KnowledgeCatalogCard/KnowledgeListView/FormulaCompareView/formulaShelf.test.ts，增补 KnowledgeDetailView.test.ts；知识目录文件仍命中现有 knowledge 前端回归簇，未改变 Test-Policy 的依赖规则。
- 新 opt-in E2E：frontend/e2e/knowledge-library.spec.ts。只有显式 KNOWLEDGE_LIBRARY_ACCEPTANCE_ROOT 且5175、隔离数据库路径/端口检查通过才允许注册及审核发布验收记录；普通 test:e2e 默认跳过该写入场景。实测记录见 docs/workflow/tasks/2026-11-25-knowledge-library.md。

## 2026-11-25 第二轮精细化

- KnowledgeCatalogCard 改为浅白底、核心属性浅绿块、同排等高与底部操作对齐；名称保留换行，长别名可展开，类型与目录分开标注，主链接扩大点击范围且避让来源/关联/比较等独立操作。KnowledgeTypeIcon 仅知识导航使用，不改变首页/社区图标。
- 用户提供并允许使用 img/黄芪.png、img/陈皮.png，复制到 assets/herbs/；herbImages.ts 仅按 HERB + 完全匹配规范名选择参考照片，无图时无占位。当前接口仍无图片字段或审核附件关系，这两张图不宣称属于某知识版本的审核附件，也不按别名、近似名或标题推断图片。
- KnowledgeEvidence.vue 抽取已提供的资料名、原文定位、摘录、知识版本及原资料路由，纯文本渲染、无额外资料请求；可用于后续引用展示，但本次无RAG功能。KnowledgeRead 复用它；详情目录按真实章节追踪当前位置，关联依据支持定位到具体证据，版本与纠错归入质量维护区域，权限条件不变。
- 比较成功后收起候选、自动聚焦/定位结果，调整对象恢复原搜索/分页/已选；变更对象清除旧结果。三类ID组成标记及图例，支持仅隐藏共同药材；不比较疗效或按名称合并，不改变原始组成。表格内双向滚动固定表头与维度列。选择store和刷新清空策略未改。
- 新测试 KnowledgeEvidence.test.ts，增补 FormulaCompareView.test.ts；现有knowledge测试簇会自动覆盖，无新增生产接口/数据表或测试策略依赖规则。完整文件、截图及本轮证据见 docs/workflow/tasks/2026-11-25-knowledge-polish.md。
