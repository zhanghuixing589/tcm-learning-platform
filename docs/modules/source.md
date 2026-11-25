# 来源文件与解析

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

SourceWorkflowIntegrationTest、SourceView.test.ts、test_documents.py；共享回归 KnowledgeWorkflowIntegrationTest / PersonalCenterIntegrationTest；浏览器 stage-two.spec.ts 按需要执行。验证上传类型/大小、去重授权、他人对象、冻结、解析失败/重试、旧回写及停用影响。
