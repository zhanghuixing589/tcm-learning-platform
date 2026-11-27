# 中医科普学习平台

这是一个把可信中医知识、智能问答、学习测验、个性化推荐和社区结合起来的科普学习平台。

已完成第二阶段“公共版本、来源审核与知识中心”的主流程及本次验收。Java、Vue、FastAPI 工程及账号权限闭环、个人中心保留；新增来源上传解析、知识草稿、专业审核、发布与知识浏览。实际范围和证据见 [第二阶段记录](docs/10-第二阶段实现与验收.md)，第一阶段证据见 [第一阶段验收](docs/07-第一阶段验收.md)。其余业务按 [开发顺序与验收](docs/05-开发顺序与验收.md) 实施。

第三阶段第一批已接入分区、成员、讨论、版本化评论、文章参考文献和长文扩容，以及对应社区与管理界面；已完成验收，证据见 [第一批实现与验收](docs/11-第三阶段第一批实现与验收.md)。用户自建、自动治理、举报申诉和专业范围复核仍按后续批次实施。

## 本机启动

在项目根目录使用 PowerShell。本次已准备 JDK17、前端依赖与独立 Python3.11 虚拟环境。启动助手使用独立的本地验收 MySQL，不连接或修改已有 MySQL 服务。

```powershell
# 首次构建，含真实 MySQL 集成测试
.\scripts\test-backend.ps1

# 启动三端（后台隐藏进程，日志在 runtime/）
.\scripts\dev.ps1 start

# 显式创建一个管理员；交互输入用户名与密码，无默认账号
.\scripts\create-admin.ps1
```

访问 http://127.0.0.1:5177。主工作区默认后端8084、AI8004，三端固定绑定127.0.0.1。MinIO宿主机端口19000/19001（容器内部9000/9001）。普通用户可自行注册；只有显式创建的管理员可处理资格申请与角色授权。管理员不自动拥有专业审核权限。

隔离工作区须配置独立的 `runtime/dev-ports.json` 或显式传入三个端口；缺少配置会拒绝启动，端口占用不会自动跳号。既有stage-three-batch-one工作区预留前端15178、后端18085、AI18005；旧5174/8081/8001为历史验收入口。配置与命令见 [开发工作流](docs/workflow/README.md#开发端口与多工作区)。停止时使用对应工作区的 `dev.ps1 stop`。

```powershell
# 停止应用，再停止独立数据库；保留数据目录供后续重启
.\scripts\dev.ps1 stop
python scripts\dev_mysql.py stop
```

不同机器的 JDK/Maven 路径可通过脚本参数传入。首次安装依赖、手动配置真实开发库与运行检查见 [本地开发](docs/08-本地开发.md)。`runtime/` 保存本机随机凭据与临时数据，已加入忽略规则，不能提交或分享。

## 阅读顺序

日常开发与跨会话恢复先看 [标准开发工作流](docs/workflow/README.md)、[模块索引](MODULE_INDEX.md) 和 [当前开发状态](DEVELOPMENT_STATE.md)。项目规则见 [AGENTS.md](AGENTS.md)，按需 Skill 位于 `.agents/skills/`；下列完整设计文档按任务查阅，不要求每轮顺序通读。

1. [项目架构](docs/01-项目架构设计.md)
2. [字段字典](docs/02-数据库字段字典.md)
3. [关系与业务规则](docs/03-数据库关系与业务规则.md)
4. [95项功能映射](docs/04-功能与数据库映射.md)
5. [接口契约](contracts/README.md)
6. [检索投影](database/检索投影设计.md)
7. [领域ER图](docs/diagrams/README.md)
8. [开发顺序](docs/05-开发顺序与验收.md)
9. [数据库验证报告](docs/validation-report.json)
10. [第一阶段实际验收](docs/07-第一阶段验收.md)
11. [本地启动与测试](docs/08-本地开发.md)

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

上表为 V1 设计基线：69 张表、149 条外键。V4/V5 增加关注关系、候选名称/别名及上传授权；V7–V9 再增加 11 张社区版本、文献、成员、额度和写入控制表，当前共 84 张业务表。增量字段与约束见 [第一批数据与接口设计](docs/superpowers/specs/2026-11-20-stage-three-batch-one-data-api-design.md)。

## 迁移入口

`backend/src/main/resources/db/migration/V1__initial_schema.sql`

`backend/src/main/resources/db/migration/V2__seed_roles_and_categories.sql`

运行工程由 Flyway 顺序执行 V1–V9；V3 为会话绝对期限、V4 为关注关系、V5/V6 为版本候选名称与文件上传授权/文件名。V7/V8 是 `backend/src/main/java/db/migration/` 中的 Java 迁移，负责社区规范化预检和旧版本快照；V9 SQL 增加额度、幂等和限频表。不手工跳过或修改已执行版本。

脚本不创建数据库、不选择数据库、不包含DROP，不创建账户或真实密钥。仅对新建空库按V1→V2执行；后续由Flyway管理，已执行迁移不原位修改。DDL失败不能依赖事务自动撤销。

## 本地验证

Windows已有MySQL8.0时，可执行：

```powershell
python scripts/validate_mysql.py --mysql-bin "D:\develop\mysql8.0\bin" --work-dir runtime/schema-validation --project .
```

验证工具自行创建临时数据目录，使用独立共享内存名并禁用网络，不连接现有MySQL服务；完成后关闭临时实例。报告覆盖数据库层，不等同于六模块业务、模型、Neo4j或前后端整栈验证。
