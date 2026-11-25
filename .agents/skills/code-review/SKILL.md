---
name: code-review
description: Use when reviewing the current task's staged, unstaged, or branch changes before delivery in tcm-learning-platform.
---

限定本次任务范围，先查 diff --stat 再读相关 diff；新文件不在普通 diff 中，需单独查看。与开始前修改区分，必要时读相邻调用者而非扫描全仓。

逐项核对：需求与设计一致；对象归属/角色隔离；事务/版本锁/幂等；公开版本与来源有效性；异步旧回写；迁移兼容；前端失败/空状态及旧请求污染。只检查变更触达的风险，通用边界沿用根规则。

对照 Test-Policy 的范围与真实证据，检查缺失消费者及未验证链路；接口或路径变化核对 contracts、MODULE_INDEX 与模块文档是否同步。

只报告有证据的可操作问题，给文件/行、触发条件、后果、优先级；没有发现也写残余风险与测试边界。单代理自审即可，不默认调用其他代理；发现问题后仅重跑受影响检查。
