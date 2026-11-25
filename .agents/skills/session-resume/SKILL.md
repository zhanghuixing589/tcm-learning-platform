---
name: session-resume
description: Use when starting a new session on an unfinished project task, handing work over, or recording a development checkpoint.
---

读 [开发状态](../../../DEVELOPMENT_STATE.md)，核对分支/HEAD、git status 与任务相关 diff。保留开始前修改；状态过期先纠正差异，不能按旧摘要撤销当前代码。

只沿状态中“下一步”和模块索引恢复必需上下文。旧 task_plan/findings/progress 仅在追溯历史时查相关章节，不每轮全部读取。

检查已记录测试的版本/环境适用性；有相应变化再执行受影响测试，不自动重跑全量。阻塞时保存最小复现和已排除假设，继续可独立完成的授权工作。

交接时按开发状态的字段约定写完成项、修改文件、准确命令/结果/日志、未验证项、决策引用和下一条具体动作。长任务单独建任务文件，根状态只作索引。
