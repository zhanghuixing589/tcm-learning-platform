# 第二阶段：公共版本、来源审核与知识中心

依据 docs/05-开发顺序与验收.md 第二阶段及用户“按照流程来开发”的授权实施。沿用已确认的架构、数据库、界面系统及权限模型，不重开技术路线选择。

## 范围及职责

Java 维护真实业务状态、权限、事务、受控文件和任务；Python 处理文档解析，不连接业务库；Vue 提供学习者知识中心、贡献者工作台和专业审核台。复用既有账号、个人中心收藏历史、资源身份及不可变版本。统一 ApiResponse/PageResult，全部 ID 字符串，UTC 时间。

依赖顺序：公共版本/来源证据模型 → 资料上传和解析 → 知识草稿/药材方剂维护 → 专业审核和正式发布 → 用户知识中心与纠错/对比。既有资源版本已有部分实现，本轮按统一事务规则补齐，不重复创建并行的内容系统。

## 业务约束

- 贡献权限 contribution:submit；专业审核 knowledge:review；技术任务管理 system:job:manage。ADMIN 不自动有专业审核权。
- 只有本人 DRAFT 可以修改；IN_REVIEW/APPROVED/REJECTED/WITHDRAWN 均冻结，退回或修订复制新版本。
- 审核使用 review_tasks.lock_version；自审拒绝，并发或重复不同决策返回 409。允许审核通过后重复相同决策返回已有结果。
- 发布锁定任务和资源，检查证据来源 APPROVED/ACTIVE，再更新正式指针、规范名/别名索引、审核事件、审计、通知、幂等索引任务，同一事务提交。
- 知识类型 CONCEPT/HERB/FORMULA/EFFECT/SYMPTOM/MERIDIAN；药材属性、方剂组成、知识关系和证据沿用关系表。候选规范名/别名按版本暂存，审核通过才刷新正式检索名。
- 方剂药材和关系指向有效已审核目标及具体版本，依据必须属于本稿。未经审核的稿件、来源和私有文件不得出现在公开查询。
- 来源停用列出受影响知识并停止合格检索；恢复需新来源/知识审核，不直接批量恢复下游。
- 文档支持 UTF-8 TXT、DOCX 和有文本层 PDF；不宣称具备 OCR。文件按实际格式校验与 SHA256 去重，随机/哈希存储键，访问始终鉴权。解析失败保留原因和重试入口。
- 每次解析建立新批次，旧片段不覆盖。Java 领取任务生成 runToken，按任务租约和 token 条件回写，旧执行不能覆盖新结果。资料重新提交创建 parent_document_id 新记录。
- 条目整理提供人工确认的片段转草稿及批量导入，不把确定性处理描述成模型抽取；真实 RAG/Chroma/Neo4j 在第四阶段实现。发布只产生明确待处理的索引任务，不伪装成已完成索引。

## 冻结的前后端接口

所有路径以下省略 /api/v1。响应为现有 Envelope.data。请求/响应使用 camelCase。

### 来源资料与文件

- POST /files，multipart file，返回 {id,originalName,mimeType,byteSize,sha256,deduplicated}，最多 10MB。
- GET /files/{id}：仅可访问本人上传、本人关联资料、专业审核可见资料或已审核有效资料所关联文件；不返回存储键。
- GET /source-documents?page=1&pageSize=20&q=&state=&approved=false：默认本人列表，approved=true 为已审核 ACTIVE 来源；审核者可见所有。返回 Page<Document>。
- POST /source-documents：{fileId?,title,sourceType,author?,publisher?,publicationYear?,sourceUrl?,bibliographicNote?}；初始 DRAFT。只允许本人已上传文件，WEB URL 仅作书目信息，不由服务端任意抓取。
- GET /source-documents/{id}：Document，包含最新 parseRun、fileName。
- PATCH /source-documents/{id}：相同字段，仅本人 DRAFT。
- POST /source-documents/{id}/parse：新建解析批次和任务，返回 parseRun；仅本人/审核者，不能覆盖已提交内容。
- GET /source-documents/{id}/segments：Page<Segment>，page/pageSize，默认最新成功批次，可选 parseRunId；{id,documentId,parseRunId,segmentNo,pageLabel,headingPath,body}。
- POST /source-documents/{id}/submit：冻结并创建 SOURCE 审核任务，返回 Document。
- POST /source-documents/{id}/withdraw：本人撤回待审核，返回 Document。
- POST /source-documents/{id}/revise：复制为新资料记录，返回 Document。
- POST /source-documents/{id}/suspend：审核权，{reason}，返回 {affectedResources:[{id,title}]}。
- Document：{id,title,sourceType,author,publisher,publicationYear,sourceUrl,bibliographicNote,fileId,fileName,reviewState,availability,submittedBy,parentDocumentId,createdAt,parseRun:{id,status,errorMessage,parseVersion}|null}。
- GET /admin/jobs：分页，status/jobType 可筛选；POST /admin/jobs/{id}/retries：技术权限，仅失败/到期任务重试。索引任务暂不执行，页面明确“等待第四阶段索引服务”。

### 知识草稿、版本及公开查询

- GET /knowledge/categories：[{id,name,code}]。
- GET /knowledge?page=1&pageSize=20&q=&type=&categoryId=：只返回有效正式版本，Page<KnowledgeSummary>。
- GET /knowledge/{resourceId}：正式 KnowledgeDetail，记录本人历史。
- GET /knowledge/{id}/relations：可见正式关系列表。
- GET /contributions/knowledge：本人稿件 Page<KnowledgeDetail>（q/state 可筛选）。
- POST /knowledge：创建知识草稿，KnowledgeInput → KnowledgeDetail。
- GET /knowledge/{id}/draft：本人或审核者最新稿。
- PATCH /knowledge/{id}/draft：KnowledgeInput，仅本人 DRAFT。
- POST /knowledge/{id}/revise：复制最新冻结版本并复制子表，返回新稿。
- GET /knowledge/{id}/versions：本人或审核者可见全部，普通用户仅已公开版本；GET /knowledge/{id}/versions/{revisionId} 同样鉴权，返回完整版本快照用于前端对比。
- POST /revisions/{revisionId}/submit、/withdraw：本人，返回 KnowledgeDetail。
- POST /knowledge/import：{items:KnowledgeInput[]}，1–50 项，全部校验后事务生成草稿，不直接发布。
- POST /knowledge/extractions：{documentId,segmentIds:string[],type}，从本人或已审核可用来源片段生成待人工校正的草稿，返回 {items:KnowledgeDetail[]}。
- POST /knowledge/{id}/suspend：审核权，{reason}；恢复通过新版本审核完成。
- POST /resources/{id}/feedback：{content}，本人绑定当前正式版本；GET /contributions/feedback 为本人/审核者列表，PATCH /feedback/{id} 审核权 {status,resolution}。
- POST /formulas/compare：{ids:string[]}，2–3 个已公开有效方剂，返回 {formulas:KnowledgeDetail[]}，前端按组成/功效/注意事项并排比较，不解释为诊断。
- 收藏/历史复用 /resources/{id}/bookmark 和 /me/history，不建立第二套。

KnowledgeInput：{type,canonicalName,title,body,categoryId?,changeNote?,aliases:string[],herb?:{nature,flavor,originNote,precautions},formula?:{originText,functionSummary,indicationSummary,precautions},evidence:[{documentId,segmentId?,locationLabel?,quoteText}],ingredients:[{herbResourceId,herbRevisionId,role,doseValue?,doseUnit?,doseText?,evidenceIndex:number}],relations:[{targetResourceId,targetRevisionId,relationType,explanation?,evidenceIndex:number}]}。

KnowledgeSummary：{id,revisionId,type,canonicalName,title,summary,categoryId,categoryName,visibilityStatus,revisionState,versionNo,createdAt,updatedAt}。
KnowledgeDetail：Summary + {body,ownerId,lockVersion,aliases,herb,formula,evidence:[{id,documentId,segmentId,locationLabel,quoteText,documentTitle}],ingredients:[{id,herbResourceId,herbRevisionId,herbName,role,doseValue,doseUnit,doseText,evidenceIndex}],relations:[{id,targetResourceId,targetRevisionId,targetName,relationType,explanation,evidenceIndex}],reviewNote}。

### 专业审核

- GET /reviews?page=1&pageSize=20&status=PENDING：知识/来源任务，Page<Review>，排除本人的待审任务或明确禁止自审。
- GET /reviews/{id}：{...Review,document?:Document,knowledge?:KnowledgeDetail,events:[]}。
- POST /reviews/{id}/claim：{lockVersion}。
- POST /reviews/{id}/decisions：{lockVersion,decision:'APPROVED'|'REJECTED',note}。
- Review：{id,revisionId,documentId,title,kind:'SOURCE'|'KNOWLEDGE',submittedBy,submitterName,reviewerId,status,reviewNote,lockVersion,createdAt}。

## 页面与交互

沿用白底、深绿、浅灰绿，1120px 内容宽度。知识中心用搜索/分类、清晰列表、类型标签；详情用正文/结构化事实/来源依据与关系。贡献工作台区分资料、知识草稿、纠错；编辑器采用文字与结构化表单，不向用户暴露 JSON。来源页展示解析状态、原文片段、审核状态和明确失败重试。审核页呈现来源/知识事实、依据及新旧版本，审核意见必填。技术任务和专业审核分开权限。

页面：/knowledge、/knowledge/:id、/formulas/compare、/contribute、/contribute/sources/:id、/contribute/knowledge/:id、/reviews、/reviews/:id、/admin/jobs。更新导航、首页真实入口和路由权限，保留现有个人中心。

## 验收

后端真实 MySQL 测试覆盖所有权/权限、自审、冻结、退回复制、修订不覆盖正式版、证据和关系归属、乐观锁、重试、文件去重/访问隔离、解析失败、公开过滤。Python 覆盖格式、大小、坏文档、鉴权与非空片段。浏览器用隔离验收账户跑上传→解析→来源审核→知识草稿→知识审核→知识详情与来源追溯，并覆盖旧个人中心/身份流程；构建、单测、375/1440 无溢出及页面截图检查。失败与未覆盖项写入实际验收文档，不以模型或索引未接通冒充完整 RAG。
