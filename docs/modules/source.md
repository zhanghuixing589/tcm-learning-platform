# 来源文件与解析

## 2026-11-27：第二版实施与业务验收完成

用户已按D18完成第二版实施，依据[第二版设计](../superpowers/specs/2026-11-26-source-reading-design.md)及[统一契约](../../contracts/source-reading-v2.md)。V11/V12/V13已执行；流式MinIO、租约输入、V2产物接收、不可变阅读单元/目录、四项授权、选文/个人摘录、引用预览、独立提案及审核覆盖已实现。真实EPUB/实际书、DOC/PDF/DOCX/TXT及三条浏览器业务验收通过范围见[交付验收](../workflow/tasks/2026-11-26-source-reading/acceptance.md)，不把局部通过合计成未运行的全量命令。

主要新增Java入口为SourceImportService、SourceReader/SourceReaderController、SourceReadingData、SourceRights/SourceEvidence、SourceInputController及KnowledgeReading/KnowledgeProposals；Python process_api/processing/reader/conversion；前端SourceWizardView/SourceOverviewView/SourceReaderView及复用六类型KnowledgeDraftForm。旧SourceView仅保留旧片段定位兼容。原始文件和安全衍生件位于私有MinIO；历史LOCAL读取兼容，迁移显式执行并保留原件/授权/备份，不随服务启动自动迁移。

独立MinIO已在tcm-learning-platform Compose项目创建，入口/凭据文件位置及启停见[infra说明](../../infra/README.md)；6个历史LOCAL原件已备份迁移，保留原件与授权，容器重建后全部读回校验通过（v2-storage-after-recreate.log）。原EPUB交付历史保留如下，不与本次重构验证混用。

2026-11-25 EPUB增量：SourceFiles→EpubFiles校验有界未加密EPUB；Python documents→epub.epub_segments按OPF spine顺序提取书名/章节/段落纯文本，复用既有headingPath/pageLabel及来源任务/审核。前端SourceView新增.epub。新增EpubValidationTest、ai-service/tests/test_epub.py，均由既有全Java/AI测试发现规则覆盖，无Test-Policy更改。2026-11-26兼容实际书籍的STORED数据描述符：Java改用ZipFile读取中央目录，核对长度/CRC并保留容量/路径/压缩比限制；只短暂暂存原始压缩包，finally自动清理，不按成员路径解压。没有数据库迁移或权限扩展；真实服务及浏览器验收边界见../workflow/tasks/2026-11-25-epub.md。

状态：第二阶段已实现。详细依据：`docs/superpowers/specs/2026-11-19-stage-two-design.md` 的来源/任务章节及 `docs/10-第二阶段实现与验收.md`。

## 结构与数据

Java SourceController、SourceFiles、SourceService、SourceAccess、SourceJobs、SourceParser、SourceScheduler；Python `ai-service/app/document_api.py` 与 `documents.py`；前端 SourceView / SourceRead / WorkbenchView / JobsView。

file_objects 保存受控文件对象，file_upload_grants（V5/V6）保存每位上传者授权与文件名；source_documents 是来源身份，document_parse_runs / document_segments 保存解析批次和不可变原文；async_jobs / async_job_attempts 跟踪执行。字段字典属于 V1 快照，V5/V6 增量必须一起看。

## 生命周期与边界

上传 → 创建来源 → 请求解析 → 预览片段 → 提交冻结 → 审核 → 可用来源。审核状态和可用状态独立；修改冻结内容通过复制/修订，不覆盖历史片段。相同文件全局去重不授予其他用户读取权限，也不能泄露首个上传者的私有文件名。

Java 向 `/internal/v1/parse` 传 `{fileName,contentBase64}` 和内部凭据/traceId，Python 返回 parserName、segments。浏览器不能指定文件系统路径。执行由 Java 保留 jobId/parseRunId/runToken 并做租约和条件回写；晚到旧任务不能覆盖新执行。

来源停用会影响证据、知识及个人中心可见性，因此停用不能作为 source 单模块操作验收。索引任务当前等待后续服务，不能因任务存在就宣称检索索引已完成。

## 接口与测试

`/files`、`/files/{id}`、`/source-documents` 及 `{id}` 下 parse/segments/submit/withdraw/revise/suspend，`/admin/jobs` 与重试，精确路径和权限看 SourceController。

SourceWorkflowIntegrationTest、SourceView.test.ts、test_documents.py；共享回归 KnowledgeWorkflowIntegrationTest / PersonalCenterIntegrationTest；浏览器 stage-two.spec.ts 按需要执行。EPUB专项 frontend/e2e/epub.spec.ts 覆盖真实上传/解析/审核、章节顺序、纯文本、下载一致性及未审核来源的越权拒绝；可通过 EPUB_ACCEPTANCE_FILE 加入本机书籍，并用 runtime/epub-actual-expected.json 核对完整片段摘要。验证上传类型/大小、去重授权、他人对象、冻结、解析失败/重试、旧回写及停用影响。
