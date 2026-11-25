---
name: incremental-test
description: Use when selecting or running validation for changed code, configuration, migrations, dependencies, or contracts in tcm-learning-platform.
---

先运行 `./scripts/test-changed.ps1 -PlanOnly`；分支任务加 `-Base <base-ref>`。审查 diff 和消费者，依据 [测试规则](../../../docs/workflow/README.md) 补齐动态依赖与浏览器业务链。

运行一次合并后的必要计划，或用 `test-module.ps1 -Module <name> -Layer <layer>` 定向验证。Layer 缩小需有变更范围依据；不能用它绕过完整计划的安全/迁移覆盖。

成功证据在 runtime/workflow/*/{plan,result}.json 及日志。复用旧结果必须确认源码、传递依赖、配置、迁移、锁文件、命令、输入和环境一致；脚本不自动跨会话跳过测试。

预览、未选择测试、缺少专项覆盖不等于通过。新测试入口/依赖关系改变时同步 Test-Policy 与其场景自检，再更新状态。
