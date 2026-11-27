# 来源阅读 V2 实施契约

2026-11-26用户已授权按第二版开发，包含D2-01/02/03推荐边界。此文件为本轮统一实现契约，完成后按实际验证更新状态；旧公开接口/segments兼容。

## 处理输出与读取

- `reading`结构：`schemaVersion:2,format,layoutMode,availableReadingModes,capabilities,metadata,outline,units,assets,warnings,overallOutcome,stages`。
- 模式STRUCTURED/FIXED_LAYOUT/PLAIN_TEXT/ORIGINAL_PREVIEW；能力`hasToc,hasImages,hasTables,hasFootnotes,textSelectable,searchable,preciseCitationSupported,fontSizeAdjustable`。每unit也有能力，不能只看全书开关。
- `units[]:{unitNo,unitType:CHAPTER|PAGE|TEXT_SECTION,title,sourcePath,pageNumber?,nodes,blocks:[{blockNo,localId,text}],capabilities,availableReadingModes,warnings,previewRef?}`。
- 为尚未发布的第一版前端临时保留`chapters`/`chapterNo`别名可行，但真实模型和主接口用units，不能将页称为原书章节。
- AST沿第一版安全节点，增加figure/figcaption/footnote等受限语义；图/预览用assetRef，不能有任意HTML/CSS/外部资源。
- `outline:[{title,unitNo,localId?,children}]`，与正文顺序解耦。
- `assets[]:{ref,path?,mimeType,bundlePath?,kind:IMAGE|PAGE_PREVIEW|DERIVED_DOCX|DERIVED_PDF,originalFormat?,unitNo?,pageNumber?}`。
- warnings使用`{code,message,unitNo?,assetRef?,severity}`或兼容字符串；局部失败保留位置。overallOutcome=SUCCEEDED/PARTIAL_SUCCESS/FAILED，stages区分metadata/conversion/preview/structure/text/media，不把存储成功当阅读成功。

## 大文件内部通道

- 新`POST /internal/v2/process`输入`{jobId,runToken,fileName}`，既有X-Internal-Token认证。
- Python只从固定配置BACKEND_URL拉取`/internal/v1/source-inputs/{jobId}`，头X-Internal-Token和X-Job-Run-Token；Java检查RUNNING+未过期租约，不能给调用者指定路径/任意URL。
- 原件流式落受限任务临时目录，校验大小/hash；不走50MiB base64。输出受限ZIP包：result.json（parserName/segments/reading）及assets声明的bundlePath二进制；无图片base64。Java流式接收临时包、验证成员路径/累计大小/CRC/图片实际类型/像素后入私有MinIO。
- 原DOC保留原件；隔离转换输出DOCX/PDF衍生件。预览PNG/GIF首帧/WebP/SVG/BMP/TIFF转换均由受限适配器生成。固定页/扫描PDF无文字时只预览，不能伪造segments。

## 公共读取与整理

- Reader `GET /source-documents/:id/reader?editionId`：`{document,edition:{id,readingVersionNo,parseRunId?,processingRunId,parseVersion,format,status,layoutMode,availableReadingModes,capabilities,metadata,warnings,overallOutcome,stages},outline,units:[{id,unitNo,unitType,title,pageNumber,capabilities,availableReadingModes,previewRef?}],assets:[{id,ref,mimeType,kind}],progress,capabilities:{read,extract,download}}`。可暂返chapters别名，但主路径units。
- `GET /source-documents/:id/reader/units/:unitId?editionId&mode`返回unit和blocks数据库ID；图片`/reader/assets/:assetId`仅返回安全图片。原PDF与DERIVED_PDF交付另受原件许可，公开download=false时不能交付字节；改用PAGE_PREVIEW。
- `GET /source-documents/:id/reader/search?editionId&q&page&pageSize`返回PageResult，item `{unitId,unitNo,blockId,localId,offset,endOffset,text}`；范围Unicode码点。
- 进度GET/PUT `/source-documents/:id/reading-progress`，PUT `{editionId,unitId,blockId?,offset?,pageNumber?,fontSize?,readingMode?,expectedVersion?}`，本人可见且版本校验。
- 摘录POST/GET `/source-documents/:id/excerpts`，POST `{editionId,ranges:[{blockId,start,end}],quoteText}`，返回`{id,documentId,documentTitle,parseRunId,readingEditionId,locationLabel,quoteText,quoteHash,locator}`；服务端重建，多块LF连接。
- 个人跨资料摘录`GET /me/source-excerpts`。向本人草稿追加`POST /contributions/knowledge/:id/evidence`输入`{excerptIds,expectedVersion}`。
- 确认上传/rights/知识/提案协议沿第一版contracts，但来源增加approvedUse与processing覆盖；确认请求具有幂等requestKey。
- 权利`rights:{licenseType,copyrightSource,authorizationScope,publicReadingAllowed,publicDownloadAllowed,quoteDisplayAllowed,ragUseAllowed}`；SOURCE已审+ACTIVE+许可后才公开，原件/图片/搜索/引文都需门禁。
- SOURCE部分成功审核必须记录warning确认及批准覆盖；REFERENCE_ONLY可审可读，但禁精确文字引用/RAG。知识需来源已审有效、引用可靠、引用授权，且知识通过审核；不自动索引。
- Proposal请求/响应沿第一版计划，独立于原作者content_revision草稿；Review kind KNOWLEDGE_PROPOSAL，详情proposal。原owner权限不移除。

限额候选50MiB原件/200MiB解压/5000 ZIP项/200倍，配置统一；文字200万/检索片段1万先保留。DOC及复杂预览适配器属于同一Compose项目，独立受限容器，不安装OCR，不改网络模式。

## 本轮集成补充

- 所有外部路径带`/api/v1`，统一ApiResponse；ID为字符串、页码从1起、时间UTC。未获对象授权返回404；无操作权限403、字段/格式错误400、版本/状态/基线冲突409。输入严格拒绝未知DTO属性；详情的id/documentTitle等展示字段不能原样作为KnowledgeInput发送。
- `POST /source-documents/confirm`接收平铺SourceInput及requestKey（8—80位字母数字下划线或连字符），可含documentId/expectedVersion；先验证实际上传授权和版权，再同事务创建/更新来源与一个异步处理任务。同人同key同内容幂等（对象字段顺序不影响），冲突内容409。
- licenseType枚举UNKNOWN/OWNED/AUTHORIZED/PUBLIC_DOMAIN/OPEN_LICENSE，copyrightSource/authorizationScope各最多4000字。UNKNOWN或缺少声明不能开放四项授权；RAG需要公开引文授权。`rightsStatus=LEGACY_UNCONFIRMED`保留迁移前已发布引文展示，不授予整书/下载/新知识发布权限。
- `POST /source-documents/:id/reader-build`追加不可变阅读构建，面向有成功文字批次的来源，不改旧segments/成功状态/审核冻结指针；`POST .../parse/retry`让所有者重排失败任务。SourceDocument增加processingJob/readerBuild（id/status/errorMessage），执行中QUEUED/RUNNING/RETRY_WAIT应持续轮询；parseRun仅表示处理批次本身。
- 来源审核`POST /reviews/:id/decisions`除lockVersion/decision/note外，V2阅读版本批准须带acknowledgedWarnings（警告code或原字符串）、approvedUse、approvedCoverage:{unitIds}、coverageSnapshotHash。覆盖只能选服务端可靠文字单元；REFERENCE_ONLY覆盖为空，不能精确引用或索引。无阅读版本的兼容书目来源仍需声明版权，可使用既有审核字段。
- `POST /source-documents/:id/rights/restrict`接收expectedVersion、四项完整布尔值及reason；只可缩减授权，扩大须复制修订审核。保留权益事件；撤回公开引用会隐藏依赖正式知识并排移除索引任务，历史正文/引用不改写；恢复不由旧审核任务自动触发。
- `POST /knowledge/:id/proposals`接收baseRevisionId及knowledge；`GET/PATCH /knowledge-proposals/:id`，PATCH接收expectedVersion及knowledge；`POST .../submit|withdraw`。响应{id,resourceId,baseRevisionId,status,lockVersion,submittedBy,knowledge,publishedRevisionId?}。独立状态DRAFT/IN_REVIEW/APPROVED/REJECTED/WITHDRAWN，冻结提案不可编辑，退回/撤回后可基于当前正式版创建新提案。审批经现有reviews路径，kind=KNOWLEDGE_PROPOSAL、proposalId、详情proposal，不创建伪造版本号。
- 知识Evidence输入为documentId/segmentId?/locationLabel?/quoteText/excerptId?/parseRunId?/readingEditionId?/locator?/quoteHash?；已有可信公开引文可在无整书权限时原样用于提案，但不能修改锚点或扩大原文范围。所有新提交及审核批准复查版权、批准覆盖与来源状态；未审核贡献不会进入正式知识库。
- 精确引用版本/块/范围不可变。重建阅读版本不会覆盖旧版本；未审核的新阅读版本不向公开读者暴露。公开引文预览仅返回该条已审核引用与来源书目，不返回整章/原件；个人摘录按所有者及当前阅读/引文权限读取。
- V13追加source_documents.reviewed_rights；来源提交时冻结完整授权快照，SourceDocument.reviewedRights只读。授权缩减只改当前有效门禁并记录独立事件，不能覆盖冻结快照；迁移前不存在的审核授权快照保留null，不推断历史许可。
- 独立提案审核结果通知指向`/contribute/proposals/:proposalId`，不把提案贡献者导向原作者的知识编辑页；正式资源关联仍保留原resourceId。
