---
name: project-context
description: Use when a tcm-learning-platform task needs module paths, business rules, dependencies, or implementation boundaries.
---

先遵循根 AGENTS.md。读取 [模块索引](../../../MODULE_INDEX.md) 对应行，只加载该模块设计与任务需要的函数/规则；前端外观任务才读 DESIGN.md，产品开放范围才读 PRODUCT.md。

以路径 + 关键符号 + 简短结论保留已用上下文。文件未变且内容仍在上下文时不重读；恢复后缺少细节时按符号读取局部。可用 git diff / 单文件 Get-FileHash 校验变化，不能仅按修改时间判定。

资料优先级：用户当前范围 → 已确认决策/既有设计约束 → 实际代码/迁移与本次证据。设计与实现冲突时分别记录状态，不能凭设计宣称已实现。

找不到入口时先在关联目录 rg，再逐层扩大；不要从仓库根输出所有文件。不加载其他五个 Skill，除非任务进入其触发场景。
