# 公共设施、运行与内部服务

状态：统一响应/追踪、Java/AI 健康和能力声明已实现；任务入口在 source 包，完整运维平台与后续索引服务未完成。依据：`docs/08-本地开发.md`、`contracts/README.md`、架构文档。

## 实际入口

- common：ApiResponse、ApiException、GlobalExceptionHandler、TraceFilter、PageResult、PublishedKnowledge。它们影响全部消费者，不能只测命名相似的单个用例。
- operations/CapabilitiesController：`/api/v1/capabilities`；Java 健康由 actuator 提供。
- AI main.py / core/settings.py：内部凭据、traceId、健康与能力；document_api.py 提供解析。generation.available 当前为 false，即使配置模型密钥也不等于已实现问答。
- 前端 shared/api/client.ts / types.ts、shared/stores/session.ts、router、app 布局是公共依赖。刷新并发与跨账号晚到响应处理是安全契约的一部分。

## 数据与任务

sys_audit_logs 保存敏感操作审计；async_jobs / async_job_attempts 保存任务。user_notifications 表已预建不证明存在通知 API；索引任务排队不证明索引可用。Java 统一写业务 MySQL，Python 不直接维护业务表。

内部请求需 X-Internal-Token；健康输出也受凭据保护，浏览器不直接访问内部服务。公开响应沿用 code/message/data/traceId、ID 字符串和分页约定；错误不向浏览器返回内部堆栈。日志脱敏，runtime 状态文件仅本地使用。

## 运行和验证

`scripts/dev.ps1` 管理三端隐藏进程；`dev_mysql.py` 管理隔离库，数据库停止与应用停止分开。check-env 只检查环境，不启动/停止、不安装依赖、不创建账号。已记录 PID 或 TCP 监听不构成 SQL/业务就绪证明。

端口由`scripts/Dev-Ports.ps1`统一解析，启动/Import-DevEnvironment/check-env共用；主站5177/8084/8004、127.0.0.1回环绑定。worktree须独立runtime/dev-ports.json或完整显式参数；重复/非法端口及占用失败关闭，不自动换号。MinIO宿主机19000/19001。详见工作流“开发端口与多工作区”；Test-Policy对共享启动/环境脚本保留全层回归并增加端口行为自检。

公共后端、配置、迁移及契约变更使用保守全自动回归；公共前端测试涵盖 client/access 并执行类型与构建。AI 健康/解析用 test_health.py / test_documents.py；E2E 按受影响场景选，不默认全跑。
