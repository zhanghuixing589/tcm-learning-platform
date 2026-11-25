# 模块索引

核对日期：2026-11-24，HEAD `9341620d` 加本地未提交改动。这里只保存入口；每项实现状态以对应模块文档为准，不把空目录、数据库预建表或设计接口计为已实现。

“已实现”表示已有代码和历史验收依据，不承诺当前工作区构建通过。本次验证发现 KnowledgeService 有重复 update 方法导致编译阻塞，准确结果与位置见 DEVELOPMENT_STATE.md。

路径缩写：B = `backend/src/main/java/cn/tcm/platform/`；F = `frontend/src/`；J = `backend/src/test/java/cn/tcm/platform/`。Java 外部路由统一 `/api/v1`，下表省略此前缀。表为主要归属/使用关系，跨模块共享不表示独占所有权；完整表清单及字段看 V1–V6 和字段字典。

| 模块/职责 | 前端路径（F 下） | 后端入口（B 下） | 主要表 | API 入口 | 测试入口 | 设计/状态 |
|---|---|---|---|---|---|---|
| identity 身份、角色、资格 | modules/account/AuthView.vue、ApplicationsView.vue；modules/admin；shared/stores/session.ts | identity/api/{Auth,Account,AdminIdentity}Controller.java | sys_users、sys_roles、sys_permissions、sys_user_roles、sys_role_permissions、sys_role_applications、auth_refresh_tokens | /auth/*、/me、/me/role-applications、/admin/users | J/IdentityIntegrationTest.java、SessionExpirationTest.java、RegistrationPasswordValidationTest.java、RoleValidationTest.java；F/modules/account/passwordPolicy.test.ts、F/shared/api/client.test.ts、F/router/access.test.ts | [身份](docs/modules/identity.md)；已实现 |
| account 个人中心、资源互动、既有文章 | modules/account/AccountView.vue、AccountSettingsView.vue、ArticleEditorView.vue、PublicProfileView.vue | account/api/PersonalCenterController.java | user_follows、resource_bookmarks、resource_likes、user_resource_history、content_resources、content_revisions、community_post_profiles | /me/profile-summary、/me/articles、/resources/{id}、/users/{id}/* | J/PersonalCenterIntegrationTest.java；F/modules/account/{personalCenter,ArticleEditorView}.test.ts；frontend/e2e/personal-center.spec.ts | [个人中心](docs/modules/account.md)；已实现 |
| source 来源文件、解析与停用 | modules/knowledge/{SourceView,WorkbenchView,JobsView}.vue | source/{SourceController,SourceService,SourceJobs,SourceParser}.java | file_objects、file_upload_grants、source_documents、document_parse_runs、document_segments、async_jobs、async_job_attempts | /files、/source-documents、/admin/jobs | J/SourceWorkflowIntegrationTest.java；F/modules/knowledge/SourceView.test.ts；ai-service/tests/test_documents.py | [来源](docs/modules/source.md)；已实现 |
| content 版本、审核与依据 | modules/knowledge/{ReviewsView,ReviewView}.vue | content/{ReviewController,ReviewService,ContentSupport}.java | content_resources、content_revisions、revision_evidence、review_tasks、review_events、resource_feedback | /reviews；文章编辑入口在 account，知识草稿在 knowledge | J/KnowledgeWorkflowIntegrationTest.java、SourceWorkflowIntegrationTest.java、PersonalCenterIntegrationTest.java；F/modules/knowledge/{ReviewView,FeedbackQueue}.test.ts | [内容](docs/modules/content.md)；已实现的通用审核 |
| knowledge 知识中心、药材方剂、纠错 | modules/knowledge/{api.ts,taxonomyApi.ts,KnowledgeTaxonomyAdminView.vue,KnowledgeListView.vue,KnowledgeCatalogCard.vue,KnowledgeTypeIcon.vue,KnowledgeDetailView.vue,KnowledgeRead.vue,KnowledgeEvidence.vue,herbImages.ts,KnowledgeEditorView.vue,FormulaCompareView.vue,FormulaSelectionBar.vue,formulaShelf.ts,library.ts,library.css} | knowledge/api/{KnowledgeController,KnowledgeTaxonomyController}.java、knowledge/application/{KnowledgeService,KnowledgeTaxonomy}.java | knowledge_entries、knowledge_aliases、knowledge_revision_names、knowledge_revision_aliases、herb_profiles、formula_profiles、formula_ingredients、knowledge_relations；复用content_categories/content_tags/revision_tags（V10列与约束） | /knowledge、/knowledge/categories、/knowledge/tags、/admin/knowledge/*、/contributions/knowledge、/formulas/* | J/{KnowledgeWorkflowIntegrationTest,KnowledgeTaxonomyIntegrationTest,KnowledgeTaxonomyMigrationTest}.java；F/modules/knowledge/{editor,KnowledgeEditorView,KnowledgeTaxonomyAdminView,KnowledgeDetailView,KnowledgeListView,KnowledgeCatalogCard,KnowledgeEvidence,FormulaCompareView,formulaShelf}.test.ts；frontend/e2e/{stage-two,knowledge-library,knowledge-taxonomy}.spec.ts（后两者显式隔离环境启用） | [知识](docs/modules/knowledge.md)；已实现 |
| platform 通用响应、可见性、运维能力及 AI 内部入口 | shared/api；router；app；modules/knowledge/JobsView.vue | common/；operations/CapabilitiesController.java；任务在 source/ | sys_audit_logs、async_jobs、async_job_attempts（通知表预建不等于有通知 API） | /capabilities、/actuator/health/*；AI /internal/v1/health/*、/capabilities、/parse | 全部 J 测试；F/shared/api/client.test.ts、router/access.test.ts；ai-service/tests/test_health.py | [平台](docs/modules/platform.md)；部分实现 |
| community 社区、讨论、治理 | modules/community/{CommunityListView,DiscussionEditorView,CommunityReader,MarkdownEditor}.vue；modules/account/ArticleEditorView.vue | community/api/{CommunityController,CommunityArticleController,CommunityPolicyController,CommunityAdminController}.java | community_sections、community_post_profiles、community_comments、community_section_members、community_article_quotas | /posts、/community/sections、/community/text-policy、/me/articles；精确契约见DTO/API | F/modules/community/*.test.ts；frontend/e2e/community-experience.spec.ts；J/CommunityWorkflowIntegrationTest.java等（本轮未跑Java） | [社区](docs/modules/community.md)；第一批已实现，第二轮UI见下节，后续治理不据此宣称完成 |
| qa 检索与问答 | 无专属页面 | qa/ 为空包；ai-service 当前只有解析/健康 | qa_sessions、qa_messages、qa_runs、qa_retrieval_hits、qa_citations、qa_feedback、qa_saved_answers | contracts 中 /qa/* 为计划，不是当前路由 | 无问答专项测试；能力不可用用 test_health.py 与身份集成基线 | [问答](docs/modules/qa.md)；待实现 |
| learning 学习、试卷、错题 | 无专属页面 | learning/ 为空包 | learning_topics、topic_profiles、learning_cards、topic_cards、card_knowledge_links、question_profiles、question_options、question_knowledge_links、learning_assessments、assessment_questions、user_enrollments、user_card_progress、quiz_attempts、quiz_attempt_items、quiz_attempt_answers、user_wrong_questions | /topics、/assessments 等仅契约设计 | 无专项测试，不宣称覆盖 | [学习](docs/modules/learning.md)；待实现 |
| recommendation 推荐、事件归因 | 无专属页面 | recommendation/ 为空包 | user_tag_preferences、user_interest_scores、recommendation_strategies、recommendation_requests、recommendation_items、recommendation_feedback、behavior_events | /recommendations 等仅契约设计 | 无专项测试，不宣称覆盖 | [推荐](docs/modules/recommendation.md)；待实现 |

## 更新与定位

数据库定义入口：`backend/src/main/resources/db/migration/`；V1 为69表基线，V4/V5新增表，不能只读旧字典推断当前结构。接口总览：`contracts/README.md`；当前精确字段/状态由 Controller、DTO、前端类型及测试交叉核对。

补充公共表归属：`content_categories`、`content_tags`、`revision_tags` 属于 content 公共分类/标签与版本关联，由知识等模块复用；`user_notifications` 属于 platform 通知数据设计，已建表但不据此认定通知服务/API 已实现。结合上表覆盖当前 V1–V6 的全部73张业务表。

新增模块/测试需同时更新此表与测试策略；未知路径自动扩大测试范围。定向定位示例：`rg -n 'publishedView|PublishedKnowledge' backend/src/main/java/cn/tcm/platform/knowledge backend/src/main/java/cn/tcm/platform/common`。不要把本表作为全仓库扫描清单。

## 全站前端视觉入口（2026-11-24）

- 公共样式与布局：F/app/{tokens.css,styles.css,portal.css,PortalLayout.vue,AdminLayout.vue}；F/app/ui/{BotanicalPlate,ContentGlyph,StatusBadge}.vue。只负责呈现，不引入新API、数据表、权限或路由。
- 首页：F/modules/account/{HomeView,HomeContent}.vue、home.css；HomeContent 在登录后读取既有 stageApi.knowledge 和 communityApi.posts，分别处理失败，退出/切换账户丢弃旧回写。测试 F/modules/account/HomeContent.test.ts（新增4项）。
- 页面组合：知识目录/详情与贡献工作台用 stage.css；社区列表/阅读沿 community.css；账户沿 account.css。完整设计与参考见 DESIGN.md、docs/design/reference-analysis.md。
- 测试选择：现有 account 路径规则包含 HomeContent.test.ts，公共 app/UI 路径已有全前端覆盖，无需更改 Test-Policy 选择逻辑。此次仅前端和视觉文档，文档截图触发的未知路径降级在任务记录中解释，未触达服务端安全或迁移。
- 上表 community 的“空包/无页面”是旧工作流快照；本次核对的当前路由包含 modules/community 与三类社区管理页，已有真实列表/分区/编辑/阅读/评论/治理功能；本次未新增该业务。准确第一批范围仍见 docs/11-第三阶段第一批实现与验收.md。

## 首页第二轮优化（2026-11-25）


细节定稿：用户确认第二版为全站视觉基准，见 DESIGN.md。HomeContent.postPreviews 复用 community/reading.ts 的 communityExcerpt、account/UserAvatar，摘要保留两行截断；HomeContent.test.ts 与 communityUi.test.ts 覆盖格式边界。reading.ts 变更仍由既有全前端规则覆盖，无 Test-Policy 调整。记录：docs/workflow/tasks/2026-11-25-home-polish.md。

- 首页仍为 F/modules/account/{HomeView,HomeContent}.vue、home.css；HomeView.search 复用 /knowledge?q=，学习入口筛选 HERB/FORMULA/CONCEPT 与社区。contribution 根据登录状态与 contribution:submit 链接到登录/资格/工作台。
- HomeContent.loadKnowledge/loadCommunity 各自管理加载/失败/重试与 latestRequest，退出、换账户及卸载不接受旧回写；空知识采用紧凑单行，社区随内容数量切换排版。
- 回归入口 F/modules/account/{HomeView,HomeContent}.test.ts。已有 account 路径测试规则自动涵盖，无新增依赖/接口/后端修改，无需改 Test-Policy。验证记录见 docs/workflow/tasks/2026-11-25-home-round-two/acceptance.md。

## 社区第三轮局部体验（2026-11-25）

社区第三轮入口：community/MarkdownEditor新增kind区分讨论/文章，MarkdownToolIcon呈现工具；ReferenceEditor保留编号/模型并加入删除确认；useDiscussionDraft.draftState标记实际浏览器写入结果；ArticleEditorView.draftSaveLabel与saveBar提供服务器保存状态。SectionNavigation支持大量分区检索，列表讨论收藏与详情一致。详见 docs/workflow/tasks/2026-11-25-community-round-three.md；新增社区测试由现有全前端规则覆盖，无Test-Policy更改。
