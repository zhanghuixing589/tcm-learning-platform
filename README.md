# 中医科普学习平台

当前阶段：工程结构与数据库设计。已建立前端、Java后端、Python AI服务的目录蓝图及完整数据库迁移；尚未实现或启动业务应用。

## 阅读顺序

1. [项目架构](docs/01-项目架构设计.md)
2. [字段字典](docs/02-数据库字段字典.md)
3. [关系与业务规则](docs/03-数据库关系与业务规则.md)
4. [95项功能映射](docs/04-功能与数据库映射.md)
5. [接口契约](contracts/README.md)
6. [检索投影](database/检索投影设计.md)
7. [领域ER图](docs/diagrams/README.md)
8. [开发顺序](docs/05-开发顺序与验收.md)
9. [数据库验证报告](docs/validation-report.json)

## 数据库范围

| 领域 | 表数 |
|---|---|
| 基础权限 | 7 |
| 公共资源 | 14 |
| 知识加工 | 6 |
| 知识中心 | 6 |
| 学习测验 | 16 |
| 智能问答 | 7 |
| 个性化推荐 | 7 |
| 科普社区 | 6 |

合计69张表、149条外键。这是覆盖共享身份、内容版本、来源证据和业务历史的完整设计，不要求首轮同时开发全部表。

## 迁移入口

`backend/src/main/resources/db/migration/V1__initial_schema.sql`

`backend/src/main/resources/db/migration/V2__seed_roles_and_categories.sql`

脚本不创建数据库、不选择数据库、不包含DROP，不创建账户或真实密钥。仅对新建空库按V1→V2执行；后续由Flyway管理，已执行迁移不原位修改。DDL失败不能依赖事务自动撤销。

## 本地验证

Windows已有MySQL8.0时，可执行：

```powershell
python scripts/validate_mysql.py --mysql-bin "D:\develop\mysql8.0\bin" --work-dir runtime/schema-validation --project .
```

验证工具自行创建临时数据目录，使用独立共享内存名并禁用网络，不连接现有MySQL服务；完成后关闭临时实例。报告覆盖数据库层，不等同于六模块业务、模型、Neo4j或前后端整栈验证。
