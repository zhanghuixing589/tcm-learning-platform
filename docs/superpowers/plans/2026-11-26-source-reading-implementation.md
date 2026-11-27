# 来源阅读重构实施计划

> 已暂停：本文件是第一版获批后的历史实施契约。用户后续要求先完成第二版设计；units/能力/部分成功/转换协议等需按第二版重定稿，不可直接按本文件恢复执行。当前范围与准确代码状态见DEVELOPMENT_STATE和implementation-checkpoint。

当时用户已批准[第一版设计](../specs/2026-11-26-source-reading-design-v1.md)，并确认协作提案、版权边界与Compose分组。以下保留当时按subagent-driven-development分工的历史方案，现已被第二版设计评审范围暂停；不能据此称第二版也已批准实施。保留既有修改，不提交推送。

## 共同契约

1. 旧公共接口仍用/api/v1及ApiResponse。当前类型CONCEPT/HERB/FORMULA/EFFECT/SYMPTOM/MERIDIAN。
2. 来源DTO新增`rights:{licenseType,copyrightSource,authorizationScope,publicReadingAllowed,publicDownloadAllowed,quoteDisplayAllowed,ragUseAllowed}`、`lockVersion`、`readingEditionId`。旧字段保留。
3. `GET /files/:id/metadata`返回`{title,author,publisher,publicationYear,rightsText,outline,warnings}`；只按实际上传授权读取。`POST /source-documents/confirm`输入来源字段+rights+`documentId?`+`expectedVersion?`+`requestKey`，返回SourceDocument及真实parseRun。
4. 内部parse保留`parserName,segments`，新增`reading:{schemaVersion:1,format,metadata,outline,chapters,assets,warnings}`。
5. `chapters[]:{chapterNo,title,sourcePath,pageNumber?,nodes,blocks}`；`blocks[]:{blockNo,localId,text}`。AST节点`type`允许paragraph/heading/list/listItem/table/row/cell/image/text/strong/em/link/break/blockquote/code/sup/sub，白名单属性id/text/level/ordered/children/assetRef/href/colspan/rowspan。节点id关联规范块localId；不存在的节点属性不渲染。图片assets只含`ref,path,mimeType`，没有base64。主响应有界，不因图像突破限额。
6. 目录`outline[]:{title,chapterNo,localId?,children}`。EPUB沿nav/NCX/spine；PDF每页chapter，TXT全文。外链不自动加载。
7. `GET /source-documents/:id/reader?editionId`返回`{document,edition:{id,parseRunId,parseVersion,format,status,metadata,warnings},outline,chapters:[{id,chapterNo,title,pageNumber}],assets:[{id,ref,mimeType}],progress,capabilities:{read,extract,download}}`。
8. `GET /source-documents/:id/reader/chapters/:chapterId?editionId`返回`{id,editionId,chapterNo,title,pageNumber,nodes,blocks:[{id,localId,blockNo,text}]}`。图片走`/reader/assets/:assetId`；PDF文件走`/source-documents/:id/file`。每次资源请求再做来源/版权/版本检查。
9. 搜索`GET /source-documents/:id/reader/search?editionId&q&page&pageSize`返回PageResult，item `{chapterId,chapterNo,blockId,localId,offset,endOffset,text}`。偏移均Unicode码点。
10. 进度GET/PUT `/source-documents/:id/reading-progress`；PUT `{editionId,chapterId,blockId?,offset?,pageNumber?,fontSize?,expectedVersion?}`，返回本人进度及`lockVersion`。
11. 摘录POST `/source-documents/:id/excerpts`，`{editionId,ranges:[{blockId,start,end}],quoteText}`，返回`{id,documentId,documentTitle,parseRunId,readingEditionId,locationLabel,quoteText,quoteHash,locator}`。服务器重建并核对；本人列表GET同路径。跨块用LF拼接。
12. Knowledge Evidence向后兼容增加`excerptId?,parseRunId?,readingEditionId?,locator?,quoteHash?`。现有所有者草稿/修订照旧。`GET /knowledge/matches?q&type`返回真实候选列表，包含id/title/canonicalName/type/ownerId/revisionId/revisionState。
13. `POST /knowledge/:id/proposals`输入`{baseRevisionId,knowledge:KnowledgeInput}`；GET/PATCH `/knowledge-proposals/:id`，PATCH `{expectedVersion,knowledge}`；submit/withdraw子路径。返回`{id,resourceId,baseRevisionId,status,lockVersion,submittedBy,knowledge}`。提案不是正式content_revision，审核通过才原子生成正式候选并发布；保留所有者草稿隔离。
14. Review DTO新增`kind:KNOWLEDGE_PROPOSAL,proposalId`及详情`proposal`。提案表单只读展示；不伪造正式revision/version。来源审核详情增加rights和阅读入口。

## 分工与文件边界

- [ ] P1 解析器：只改ai-service；新增reader.py及真实格式/安全测试，旧segments行为兼容。
- [ ] P2 存储：SourceObjectStore及Minio/Legacy实现、SourceFiles存储接入/元数据、backend/pom.xml/application.yml、环境脚本与迁移拷贝工具；不改SQL迁移/SourceService/SourceJobs/ReviewService。
- [ ] P3 前端：来源向导/概览/阅读器/六类型共用整理/提案、router、TS契约及相关测试；不改Java/Python/迁移。
- [ ] P4 主代理：V11及以后兼容迁移、SourceReader/ReaderController/资产、确认幂等/rights/任务回写、Evidence扩展、协作提案/审核、后端行为测试。
- [ ] P5 主代理：接口对齐、原文件MinIO迁移、服务更新、风险增量回归、真实多角色E2E/移动/XSS、文档和最终审查。

## 每包验证

先写核心失败行为用例并确认失败，完成最小实现后运行本包专项。集成前先预览test-changed计划；涉及来源/证据/公开权限/迁移必须补齐身份及source-content-knowledge-account共享覆盖，不能按文件名绕过。

核心后端用例：来源确认重放；未授权上传/fileId；新旧授权；范围/原文伪造；追加版本；过期令牌；提案非作者写入/自审/基准过期/并发发布；旧片段与公开引用保留。最后真实文件和实际服务闭环验证，不用响应拦截假造成功。

跟踪：[任务计划](../../workflow/tasks/2026-11-26-source-reading/task_plan.md)。
