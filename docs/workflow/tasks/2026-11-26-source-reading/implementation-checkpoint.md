# 第一版实施暂停检查点

日期2026-11-26。用户先同意第一版设计，随后通过追加评审明确要求“第二版设计修订，暂不进入全部业务功能实施”。下述未完成代码保留，不回滚既有修改；三个实施代理已停止并只读交接，后续只改文档。

## 已完成、仍保留的独立基础设施

- `infra/compose.infra.yml`、`infra/minio/bootstrap.sh`、`infra/minio/source-policy.json`、`infra/README.md`、`scripts/dev_minio.py`。
- Compose项目tcm-learning-platform；MinIO API 127.0.0.1:19000、控制台19001；私有桶tcm-learning-sources，独立数据卷。
- 已验证容器健康、应用身份写/读/删除自建探针、匿名对象读取403。没有重建持久化专项结果，不声称重建已验。
- 初次脚本将Docker“volume NAME not found”误当未知错误；修正缺失卷识别后启动通过。失败与成功日志均在runtime/source-reader-design/minio-start*.log。
- 凭据只在ignored runtime/minio.env；没有输出或提交密钥；旧D:/minio/data和其他项目容器/数据未修改。

## 保留的未完成业务草稿

| 区域 | 已有修改 | 尚未闭合 |
|---|---|---|
| AI | 新增app/reader.py、tests/test_reader.py；修改documents.py和README | 只有四格式AST，整书阅读限制更严格；无DOC/多模式/部分成功。最后新增相邻pre块测试失败未修复 |
| Java存储 | 新增SourceObjectStore、SourceFileMetadata、SourceStorageMigration、存储/元数据测试；修改SourceFiles、pom、application.yml、两环境脚本；新增迁移工具脚本 | 真实应用接入依赖尚未执行的V11。元数据测试未取得结果；历史迁移工具未运行 |
| 数据库草案 | V11__source_reading.sql | 未执行，仍为第一版chapters模型，需按第二版unit/能力/处理结果收敛 |
| 核心后端 | SourceReadingIntegrationTest | 确认入口尚未实现；第一次因半成品依赖编译失败，后一次上传500，均不是正确功能RED |
| 前端 | KnowledgeDraftForm、readerApi、SourceWizardView、SourceOverviewView、SourceReaderView、ReaderAst/Outline/Pdf、readerSelection、reader.css；修改KnowledgeEditorView、api/editor、package及lock | 路由未接、提案/审核/溯源整合未完成、无新行为测试、未test/build/浏览器验收 |

原EPUB的app/epub.py、tests/test_epub.py、EpubFiles、EpubValidationTest及SourceView/测试等开始前修改保留。暂停时完整状态清单runtime/source-reader-design/paused-implementation-status.txt。

## 已执行命令及准确边界

- `scripts/test-changed.ps1 -PlanOnly`仅预览，要求全Java/前端/AI共享回归；没有执行完整计划。
- Java专项：SourceObjectStoreTest 2项、EpubValidationTest 5项通过；SourceReadingIntegrationTest在上传阶段500，未到确认入口，整命令失败。日志runtime/storage-initial-test.log含MockMvc认证cookie，保持私有，不复制全文。
- 第二次Java单位专项在testCompile阶段停止任务所属JVM59248，未有通过结果；runtime/storage-unit-test.log。
- Python一次完整43项通过对应当时版本；之后新增相邻pre块测试输出F，随后停止，未修复也未重跑全套。对应工具session8217仅作追溯，不认为当前全AI通过。
- 前端npm view返回pdfjs-dist 6.5.438，package/lock已出现该版本；安装会话32454的最终状态未作为验收记录。无前端测试、构建或浏览器结果。
- 备份runtime/source-reader-design/before-v11.sql：997846字节，SHA256 dc97764eaf14d740bd9a3263c1b8719d1ac03d01b40d0b762c3ff7f5c654a221。备份不等于迁移完成。

## 数据库与运行服务

- 只读检查确认数据库Flyway仍V10，storage_backend列不存在，V11未应用；LOCAL记录没有切到MINIO，原文件没有移动或删除。
- 没有重建/重启当前Java和AI业务服务，旧运行后端仍是此前EPUB交付版本；不能把新源码当成运行JAR行为。
- 新建前端阅读路由未接入router；现有知识编辑路由的共享表单等修改可能经Vite HMR生效，尚未验证。不能声称当前未提交前端完全通过。
- 没有提交、推送或清理历史数据。下一实施阶段先对齐第二版模型/API和可构建性，再做迁移/回归，不直接运行第一版未完成业务闭环。
