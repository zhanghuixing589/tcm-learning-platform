# 初始核查事实（第一版实施前）

以下记录初始核查时的代码与环境，不用来覆盖后续变化。用户随后启动Docker并授权建立独立MinIO，第一版曾产生部分实现；最新范围为第二版设计、实施已停止，准确状态见implementation-checkpoint.md。

基线：main / 849710e21c41ffcb08a22390e8cc0e81e22e4fc0。开始已有EPUB与治理规划等未提交修改，保持原样。状态清单runtime/source-reader-design/initial-status.txt。

| 事实 | 当前证据 | 对改造的约束 |
|---|---|---|
| 同一组件承担贡献来源与学习者追溯 | router/index.ts中sources/:id及contribute/sources/:id均使用SourceView.vue | 拆页面；保留旧路由/片段锚点兼容入口 |
| 现有上传存储是本地文件 | SourceFiles构造器、upload/read/bytes、application.yml、Import-DevEnvironment.ps1 | MinIO是本次新增接入；不能声称现有文件已在MinIO |
| SHA256全局去重，上传授权及文件名按用户保存 | SourceFiles、V5/V6 file_upload_grants | 存储迁移不换fileId，不因相同哈希授予其他人访问权 |
| 仅输出纯文本片段 | documents.parse_document、epub.epub_segments、SourceParser.parse、SourceJobs.ParsedSegment | 不能用headingPath分组假装已有富文本阅读器 |
| 现有批次不覆盖旧片段，租约/令牌拒绝旧回写 | SourceJobs.claim/complete/retry | 新章节/图片也必须在同一租约与批次校验下发布 |
| 最新解析成功才可提交来源，提交后冻结 | SourceService.parse/submit/revise、SourceJobs.claim | 不能让已审核来源走原PARSE入口回填；新增阅读构建任务 |
| 知识草稿可引用本人未审来源，提交要求来源审核通过且有效 | KnowledgeService.validate/publicationReady/submit | 阅读整理可提前进行，但不得绕过双重审核 |
| 一条知识已支持多条依据，药材/方剂事实、关系、目录、标签均存在 | KnowledgeDtos、KnowledgeService.saveChildren、FactsEditor、taxonomyApi | 提取共用编辑表单，避免做第二套字段/API |
| 药材归经/功效是关系，不是herb_profiles的自由文本列 | RELATIONS含ENTERS_MERIDIAN/HAS_EFFECT；FactsEditor关系编辑 | UI复用真实关系目标与依据，不伪造结构字段 |
| 直接修订只允许所有者 | KnowledgeService.revise调用owner；update同样限制所有者 | 他人修订分支必须确认权限/审核规则，不直接移除owner校验 |
| 依据只含documentId/segmentId/人工定位/引文 | KnowledgeDtos.Evidence、revision_evidence、KnowledgeEvidence.vue | 补版本/章节/文本位置；旧引文和旧跳转保持可读 |
| 目前所有已审有效来源可被登录用户读取，原文件下载同样较宽 | SourceAccess.visible、SourceFiles.read | 新版权策略需覆盖原文件、阅读资产、搜索及旧下载入口 |
| 批准知识只创建VECTOR_INDEX和GRAPH_SYNC任务 | ReviewService批准分支；SourceJobs任务serviceMessage | 不能声称已接入真实RAG或把来源通过等同索引发布 |
| 当前数据库有10个已执行迁移，6条file_objects记录合计1553885字节 | 只读SQL；runtime/source-reader-design/database-audit.txt | 数据量为本次快照；迁移/拷贝后需重新核对，不能据此删除孤立文件 |
| 数据库尚无本次阅读结构表及版权字段 | 同上information_schema核查 | 必须追加兼容迁移，不能仅更换前端 |

## D:/minio只读检查

- 目录只有data；其下看到.minio.sys和vibe-music-data目录。后者仅为桶目录线索，不等于API确认过的在线桶。
- 未发现minio.exe进程、MinIO Windows服务或9000/9001监听。
- docker客户端存在，但docker ps连接dockerDesktopLinuxEngine命名管道失败：管道不存在。当前不能查询容器或通过API确认服务/桶。
- 未启动Docker或MinIO，未修改数据、桶策略、环境配置和凭据；没有输出密钥。
- 设计建议使用独立中医平台私有桶；现有vibe-music-data不改写。真实启动条件与可用镜像/实例须实施前核实，不能用本地目录模拟MinIO成功。

## 参考资料

- 用户三张图：上传向导、三栏阅读及划词整理参考；本草纲目/人参内容仅为示例，不作为本次业务数据或验收成功证据。
- [W3C EPUB 3.3](https://www.w3.org/TR/epub-33/)：包元数据、spine阅读顺序及导航结构。
- [PDF.js官方示例](https://mozilla.github.io/pdf.js/examples/)：独立页面加载及canvas渲染。
- [MinIO Java SDK](https://github.com/minio/minio-java)：真实对象存储接入入口。
- [DOMPurify](https://github.com/cure53/DOMPurify)：现有前端依赖；富文本安全仍须服务端结构约束和本地资产访问控制。
