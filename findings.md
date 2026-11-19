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
# 2026-11-19 第二阶段核对

- PDFBox 使用官方当前 3.0.8 依赖声明（https://pdfbox.apache.org/3.0/getting-started.html），通过 Maven 实际解析/构建验证；不采用未核验的旧版本建议。
- 全局文件 SHA256 去重仍须记录每个实际上传者：V5 增加 file_upload_grants，避免第二个上传相同文件的用户无法合法关联或读取。
- V5 已被 RED 测试正常迁移，后续不原位修改。V6 独立保存每位上传者的文件名，避免同内容去重时泄露首位私有上传者的文件名。

- main 基线 b87376bc 已整合昨日工作。既有 content_resources/content_revisions、source_documents、证据、审核、药材方剂、任务等关系表可复用。
- knowledge_entries 与 knowledge_aliases 为正式检索名，需要按版本暂存候选名称/别名，避免修订草稿覆盖正式版本。
- 来源状态与解析状态独立，审核冻结原稿；新解析批次不覆盖旧来源片段。
- 权限已具备 contribution:submit、knowledge:review、system:job:manage；管理员默认没有专业审核权。
- 不在本阶段接入实际 RAG/向量/图谱；发布产生可观察的待处理索引任务。
