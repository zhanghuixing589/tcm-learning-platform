# 来源资料、原文阅读与知识整理重构

日期：2026-11-26；main / 849710e21c41ffcb08a22390e8cc0e81e22e4fc0。

当前目标：按用户后续“按照新的设计开始功能开发”及继续指令实施第二版，重构真实上传、解析、阅读、知识整理、审核和溯源，覆盖EPUB/PDF/DOC/DOCX/TXT并接入真实MinIO。D18取代早期仅设计的暂停范围。

状态：第二版实施及本轮业务验收已于2026-11-27完成。V11/V12/V13已执行，禁止修改已应用迁移；准确测试快照、失败复验及能力限制见[交付验收](2026-11-26-source-reading/acceptance.md)和根DEVELOPMENT_STATE。此前EPUB历史验收保留，不作为新链路的验收证据；G1与真实RAG不在本轮范围。

- [设计与验收矩阵](../../superpowers/specs/2026-11-26-source-reading-design.md)
- [第二版实施与验收结果](2026-11-26-source-reading/acceptance.md)
- [第一版至第二版差异及待确认项](../../superpowers/specs/2026-11-26-source-reading-v2-review.md)
- [第一版原件](../../superpowers/specs/2026-11-26-source-reading-design-v1.md)
- [计划](2026-11-26-source-reading/task_plan.md)
- [代码、数据库、MinIO核查事实](2026-11-26-source-reading/findings.md)
- [命令与执行记录](2026-11-26-source-reading/progress.md)
- [未完成实现暂停检查点](2026-11-26-source-reading/implementation-checkpoint.md)

开始前已有EPUB代码/文档与第三阶段第二批规划的未提交修改；本轮保留。原始状态清单runtime/source-reader-design/initial-status.txt。

MinIO时间线：初查Docker引擎未运行；用户启动Docker并授权创建后，已新建tcm-learning-platform项目私有MinIO，127.0.0.1:19000/19001，桶tcm-learning-sources，应用往返及匿名403通过。随后备份并迁移6个LOCAL原件，容器重建后全部读回校验通过。没有复用D:/minio/data或其他项目数据。

下一步：按用户反馈定向调整。当前入口http://127.0.0.1:5177；三条浏览器专项均已完成，准确分次结果与最新源码适用性见交付验收；不自动提交推送或进入后续RAG阶段。
