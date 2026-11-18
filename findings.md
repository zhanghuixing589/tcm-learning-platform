# 开发依据与发现

- 仓库当前无 pom.xml / package.json / Python 应用入口，69表、149外键、95项功能均为设计基线。
- `sys_users` 使用 BCrypt、ACTIVE/DISABLED、credential_version；`auth_refresh_tokens` 保存 token_hash / family_key / expires_at / revoked_at / replaced_by_id。
- 注册默认 LEARNER。CONTRIBUTOR 可申请，ADMIN 审批；REVIEWER 与 ADMIN 仅显式授权。ADMIN 不自动具备专业审核权限。
- 角色申请禁止本人审核。私有申请接口仅按当前用户查询。
- 本机有 JDK17、JDK21、Maven3.9.9、Node22.22.3、MySQL8.0。
- 官方兼容依据：Spring Boot3.5支持Java17（https://docs.spring.io/spring-boot/3.5/system-requirements.html）；Vite支持Node22.12+（https://vite.dev/guide/）；FastAPI配置参考 https://fastapi.tiangolo.com/advanced/settings/ 。
- Element Plus按需加载依据：https://element-plus.org/en-US/guide/quickstart.html；组件导入由unplugin-vue-components resolver处理，不全量注册。
- 新增身份管理接口写sys_audit_logs，记录操作者、traceId、动作和对象，不写密码、令牌或完整敏感请求。
- 业务数据库历史设计报告保留；当前应用状态由第一阶段验收文档维护，避免把历史DESIGN_ONLY_NOT_IMPLEMENTED误读成当前状态。
