---
name: api-contract
description: Use when changing an external or internal API route, DTO, frontend request type, error code, permission, or asynchronous callback contract in this project.
---

从 [模块索引](../../../MODULE_INDEX.md) 找到 Controller / DTO / 消费者；只读 [契约入口](../../../contracts/README.md) 相关章节，区分设计路由和实际路由。

变更前列出方法/路径、入参/出参、ID/时间/分页、身份/归属、状态冲突、错误、幂等及兼容影响。定位前端调用和类型，内部解析同时核对 Java SourceParser 与 Python document_api。

同步实现、调用者、相关契约和行为测试；不自动生成没有数据依据的 OpenAPI。共用约定复用 contracts，不在此重复。

验收至少覆盖成功、无权/他人对象、无效参数、状态冲突及适用的重复请求/旧版本回写。完成后更新对应模块文档和状态；契约变更的测试计划由 test-changed 保守扩大。
