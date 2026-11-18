# 项目架构与数据库基础 Implementation Plan

> For agentic workers: 使用executing-plans顺序执行。当前用户只授权本会话开发，没有授权代理委派。

**Goal:** 把已确认95项功能落实为工程分层、可执行MySQL迁移及可验证的关系模型。

**Architecture:** Vue门户与后台共用一个前端；Java模块化单体负责业务事务；Python负责AI检索和索引。MySQL为正式依据，Chroma/Neo4j为可重建投影。

**Tech Stack:** JDK17/Spring Boot3.5、Vue3/TypeScript、Python3.11/FastAPI、MySQL8.0.16+、Redis、ChromaDB、Neo4j。

## Task 1：确定边界与文件结构

- [x] 检查当前工作区、上级AGENTS、工具与需求上下文。
- [x] 创建docs/01-项目架构设计.md和backend/frontend/ai-service领域目录。
- [x] 明确当前没有业务实现、依赖锁文件或可运行应用。

## Task 2：设计与生成迁移

- [x] 创建database/schema_model.json及字段字典。
- [x] 生成backend/src/main/resources/db/migration/V1__initial_schema.sql。
- [x] 生成V2__seed_roles_and_categories.sql；不创建账号。
- [x] 保存docs/03-数据库关系与业务规则.md，区分数据库约束与服务端规则。

## Task 3：需求覆盖与关联文档

- [x] 映射K01—K16、Z01—Z15、Q01—Q16、L01—L18、R01—R15、C01—C15共95项功能。
- [x] 生成领域ER图、服务契约和检索投影。
- [x] 设计分阶段开发顺序与验收。

## Task 4：验证

- [x] 执行scripts/validate_mysql.py，在独立临时MySQL实例验证V1、V2及预期失败约束。
- [x] 确认docs/validation-report.json为PASSED且server_stopped为true。
- [x] 检查字典、SQL、模型、映射及Markdown本地链接一致。

验证命令（Windows，项目根目录）：
```powershell
python scripts/validate_mysql.py --mysql-bin "D:\develop\mysql8.0\bin" --work-dir runtime/schema-validation --project .
```
预期：报告PASSED，表数69，外键149，无业务账户种子，重复和跨对象错误数据被数据库拒绝，临时服务器关闭。

此计划到数据库设计交付结束。实际业务开发按docs/05-开发顺序与验收.md，从可运行骨架与用户权限开始；不将整个平台所有功能描述为已完成。
