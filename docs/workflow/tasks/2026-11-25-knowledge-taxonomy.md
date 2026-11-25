# 知识分类体系重构交付（2026-11-25）

## 实际完成

在已确认的知识中心布局上完成真实分类业务重构：固定六种知识类型、管理员动态维护的类型专属目录、可跨类型使用的通用标签。前台原生目录下拉框已移除，目录在标题下横向换行排列；全部知识隐藏该栏，切换类型清除旧目录与分页，通用标签可保留。目录为空与关键词/标签无匹配使用不同提示。未重设计首页/社区，未新增依赖，未commit/push。

管理入口 `/admin/knowledge/categories` 包含目录及通用标签两个视图。目录支持新增、改名、排序、启用/停用；标签支持新增、改名、启停。配置使用权限、乐观版本、同名唯一约束与审计；没有删除入口。编辑器限定当前类型目录，标签可保存/取消/恢复；审核阅读与版本对照呈现标签元数据。筛选、草稿写入/导入、提交和审核批准均校验类型与目录，不能通过手工请求绕过。

## 本轮文件清单

| 范围 | 文件 |
|---|---|
| 数据库 | 新增 backend/src/main/resources/db/migration/V10__knowledge_taxonomy.sql |
| 后端新增 | knowledge/application/KnowledgeTaxonomy.java、knowledge/api/KnowledgeTaxonomyController.java |
| 后端修改 | knowledge/application/KnowledgeService.java、knowledge/api/KnowledgeController.java、KnowledgeDtos.java |
| 后端回归 | 新增 KnowledgeTaxonomyIntegrationTest.java、KnowledgeTaxonomyMigrationTest.java；IdentityIntegrationTest.java和CommunityMigrationTest.java仅将最新迁移数量断言9更新为10 |
| 前端新增 | modules/knowledge/KnowledgeTaxonomyAdminView.vue、taxonomyApi.ts、KnowledgeTaxonomyAdminView.test.ts、KnowledgeEditorView.test.ts |
| 前端修改 | modules/knowledge/{api.ts,editor.ts,KnowledgeEditorView.vue,KnowledgeListView.vue,KnowledgeListView.test.ts,KnowledgeCatalogCard.vue,KnowledgeDetailView.vue,KnowledgeRead.vue,VersionCompare.vue,library.css,stage.css} |
| 导航权限 | frontend/src/router/index.ts、app/{AdminLayout,PortalLayout}.vue |
| E2E | 新增 frontend/e2e/knowledge-taxonomy.spec.ts；更新 knowledge-library.spec.ts为类型对应目录及横向选择，保留原业务验收 |
| 工作流 | scripts/Test-Policy.ps1、scripts/tests/workflow.Tests.ps1，新后端类纳入共享知识/内容回归簇 |
| 契约设计 | contracts/{README,knowledge-taxonomy}.md、PRODUCT.md、DESIGN.md、DECISIONS.md（K01）、MODULE_INDEX.md、docs/modules/{knowledge,content}.md、docs/{02-数据库字段字典,03-数据库关系与业务规则}.md、DEVELOPMENT_STATE.md |

上述Java路径相对于backend/src/main/java/cn/tcm/platform或backend/src/test/java/cn/tcm/platform，前端模块路径相对于frontend/src。开始前工作区已有的多轮视觉修改保留，不把整个工作区diff冒充本轮改动。

## 数据库迁移与兼容

V10为追加迁移，不修改已执行V1–V9，不新增重复分类/标签表。

- content_categories增加knowledge_type、legacy_archive、lock_version，添加类型CHECK、同类型名称唯一、启用/类型/排序索引及目录类型身份不可变触发器。新目录属于六类型之一；只有归档旧目录允许类型为空，且必须停用。其他scope不变。
- 按用户清单新增31项目录：CONCEPT/HERB/FORMULA各6，EFFECT/SYMPTOM各5，MERIDIAN 3。不是前端硬编码目录，后续管理修改立即由查询读取。
- 原3个知识目录保留ID、名称、code、排序、parent_id与全部版本引用，标记只读归档并停用。没有把旧“中药材/方剂”自动猜测为某个医学子类，也没有直接改写已发布版本的category_id。已有正式内容和收藏/历史继续可读。
- 新修订只继承仍启用的新目录；归档或停用目录在新稿中保持未分类，由贡献者重新选择，旧版本不变。旧草稿直接保存时须先改为有效目录或未分类。无目录仍可保存，发布继续遵循既有来源与审核规则。
- 新分类赋值数据库触发器依据knowledge_entries身份拒绝跨类型/归档/停用目录。服务层共享锁校验与提交/批准复查，避免管理员停用后继续发布。停用已发布条目的目录不等于停用知识。
- content_tags仅增加lock_version，revision_tags结构及推荐相关外键不变。标签以版本ID关联、修订复制，跨类型共享。标签停用保留旧关联/历史展示，不能用于新写入或新发布。
- 新权限knowledge:taxonomy:manage只授予ADMIN，不授予knowledge:review。旧账户和角色关系除该明确新权限外不改写。

已在主站和原有隔离验收库执行V10。迁移前本地完整SQL备份为runtime/knowledge-taxonomy/{main,acceptance}-before.sql，另记录分类身份及知识正文/分类指针指纹，不在报告输出私有正文或凭据。最终migration-verification.json确认：主站15个既有知识版本及3个旧目录身份保留；隔离库28个既有版本及3个旧目录身份保留。两个库迁移前无版本标签关联，另由专门的非空迁移测试验证旧标签关联不丢失。

主站当前为31个初始目录；隔离库另有正常验收新增目录，包含失败脚本留下的记录，没有为截图或排序断言删除测试数据。迁移后不建议独自回退旧应用；如需回退应协调应用/数据库备份恢复，Flyway不执行破坏性down迁移。

## 接口变化

详细字段、状态与兼容以[分类契约](../../../contracts/knowledge-taxonomy.md)为准。

- GET /knowledge/categories新增可选type，只返回启用且非归档目录；无type时供编辑器取全部类型目录。
- 新GET /knowledge/tags；GET /knowledge新增tagId筛选，q扩展匹配已启用标签名称。categoryId必须携带匹配type，否则400；tagId可跨类型。
- KnowledgeInput新增可选tagIds（至多20个不同启用标签）。创建省略为空，更新省略保留旧关联，显式[]清空。详情/目录新增tags、categoryLegacy、categoryActive；旧ID与字段保留。
- 新管理GET/POST /admin/knowledge/categories、PATCH /admin/knowledge/categories/{id}，以及对应/tags接口。创建201，编辑200并递增lockVersion；无权403，非法参数400，同名/旧版本/归档修改冲突409。目录类型创建后不可改，无删除路由。
- 旧仅categoryId的URL由前台清除此条件；直接API请求仍拒绝无type组合。收藏、版本、来源访问及审核原契约不变。

当前推荐、RAG检索/回答及索引执行尚未实现；本轮只接入真实标签组织和检索基础，保留原表/版本ID扩展能力，不伪装成已完成推荐或RAG。

## 测试与浏览器证据

证据目录runtime/knowledge-taxonomy/。先执行test-changed.ps1 -PlanOnly；迁移、权限和契约触发保守全回归。实际后端测试使用独立taxonomy_test_* schema，不在主站业务schema运行集成测试；迁移测试只创建/删除自己命名的全新schema。写入E2E有端口、runtime路径及隔离数据库检查，不能默认对主站运行。

| 检查 | 本次结果 |
|---|---|
| 新分类及原知识专项 | backend-first.log，16项通过，含9→10迁移兼容 |
| 全后端 | backend-all.log，174项执行，172项通过，2项因旧测试仍断言9个迁移失败；没有业务断言失败 |
| 两项修正复验 | backend-count-checks.log，仅更新期望迁移数为10后定向复验原失败两方法，2项通过；未重跑已通过的其余172项 |
| 后端打包 | backend-package.log，Java编译/重打包成功，主站及隔离部署JAR SHA256一致 |
| 全前端 | frontend-all.log，32文件167项通过 |
| 编辑/管理补验 | frontend-target-final.log，新增编辑器用例后7文件18项通过（测试文件名匹配也覆盖现有社区编辑帮助回归） |
| 最终类型检查及构建 | frontend-build-delivery.log，vue-tsc --noEmit及Vite通过；项目无独立lint脚本 |
| AI基础回归 | ai-tests.log，24项通过，既有httpx/Starlette弃用提示，未引入新依赖 |
| 工作流映射 | workflow-tests.log，32项断言通过 |
| 原知识真实E2E | library-e2e.log，1条/4步骤通过：六类型/名称别名标题检索/目录分页、详情依据及关联、收藏刷新、方剂比较、普通用户可见性；13条非医学记录通过正常来源/知识审核 |
| 新分类真实E2E | taxonomy-e2e-delivery.log，1条通过：管理员新增/改名/排序/启停、旧锁409、无权403；跨类型标签和名称检索；贡献者表单取消/恢复标签保存与刷新；正常审核发布2类知识；切类型清目录、正确空态和正式详情在目录停用后仍可读 |
| 主站打包后只读验证 | main-browser-after-repair.log及main-browser-results.json，13项通过：六类型目录归属/数量、全部隐藏、切换清除、原管理员新权限入口及四视口截图 |
| 长目录 | long-directory.log，390px下80字符连续目录名响应夹具无页面溢出；未写库 |

截图与写入E2E均检查1440、1280、768、390视口，无页面横向溢出，pageerror=0。隔离E2E目录/标签/知识均为明确标注的验收记录，不是正式医学内容，没有复制到主站。

自审覆盖目录与标签的权限、类型不可变、并发版本、停用/投稿竞态、原审核消费者、共享标签复制/更新省略兼容、历史冻结数据、异步列表与表单保存状态。其他阶段的全量E2E没有执行，不将现有空RAG模块描述为已验证检索功能。

## 修改后截图

- [主站方剂目录](../../../runtime/knowledge-taxonomy/main-formula-directory-1440.png)、[主站手机目录](../../../runtime/knowledge-taxonomy/main-formula-directory-390.png)。
- [管理端目录配置](../../../runtime/knowledge-taxonomy/taxonomy-admin-directories-1440.png)、[通用标签](../../../runtime/knowledge-taxonomy/taxonomy-admin-tags-1440.png)。
- [有内容的目录与标签](../../../runtime/knowledge-taxonomy/taxonomy-herb-1440.png)、[正式详情](../../../runtime/knowledge-taxonomy/taxonomy-detail-1440.png)、[贡献者编辑器手机](../../../runtime/knowledge-taxonomy/taxonomy-editor-390.png)。

其余视口同文件名前缀。隔离截图包含保留的先前验收配置，不清理记录掩盖布局或排序。

## 处理过的执行问题

- 旧运行JAR被Windows占用，首次repackage重命名失败；先从新classes及已有依赖启动验证，旧JAR解除占用后成功打包，最终两个环境都改为新JAR运行。隔离backend是部署目录（只有target），不假定它是源码副本；以根项目源码构建。
- 前端测试beforeEach误返回mock函数，被Vitest视为清理回调而执行网络请求，改为无返回值hook；原生未分类option的DOM value为显示文本，用selectedIndex=0验证清除类型旧目录。均仅修正测试写法，保留早期日志。
- 重复E2E时已有验收目录同为sortOrder=0；旧断言误认新目录应排第一。按契约验证完整sortOrder/id排序，不删除旧配置或更改业务排序。
- 最后JAR启动任务脚本使用了与Import-DevEnvironment.ps1相同的statePath变量，环境导入覆盖变量后误把服务记录写入MySQL状态文件。数据库内容未修改、两服务继续运行；已从仍运行且受本任务管理的后端配置恢复原连接信息，用原凭据重新认证（没有重置密码），恢复正确的MySQL/服务状态文件。脚本改用独立taxonomyServicesPath。两套dev_mysql.py start幂等检查、最终数据库指纹、主站13项浏览器验证通过；恢复中未输出凭据，临时认证文件已删除。此修正仅任务runtime启动脚本，不改变产品或原dev.ps1。

最终主站5173/8080及隔离5175/8082均运行新打包产物，原账号与业务数据保留。管理页面需具备knowledge:taxonomy:manage权限，原管理员刷新页面后可进入。
