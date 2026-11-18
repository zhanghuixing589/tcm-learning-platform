# API与服务契约基线

外部前缀/api/v1；内部前缀/internal/v1，内部接口不通过Nginx公开。第一阶段已实现账户、角色资格、用户管理与健康端点；下表其他业务接口仍为设计契约。尚未生成OpenAPI文件。

第一阶段补充接口：GET /me/role-applications；GET /admin/users；PATCH /admin/users/{id}/status；PUT /admin/users/{id}/roles；GET /admin/role-applications。角色修改要求system:role:manage，其他账户管理要求system:user:manage。用户不能在该接口修改自己的角色或禁用自己；LEARNER基础角色保留。

Cookie：tcm_refresh，HttpOnly，SameSite=Strict，Path=/api/v1/auth。refresh/logout必须携带受信任Origin和X-CSRF-Token: 1，自定义头要求浏览器预检，配合精确Origin列表防止跨站调用。生产Cookie默认Secure。登录响应只返回短期accessToken、expiresIn和用户视图，刷新令牌仅通过Set-Cookie返回。重放旧刷新令牌撤销整个登录链，旧JWT随之失效；退出、禁用同样即时撤销。

已实现Java健康：GET /actuator/health/liveness、/actuator/health/readiness，后者包含数据库检查。GET /api/v1/capabilities明确当前问答未实现。AI内部健康：GET /internal/v1/health/liveness、/health/readiness、/capabilities，请求带X-Internal-Token。

| 领域 | 主要路由 | 规则 |
|---|---|---|
| 账户 | POST /auth/register、/auth/login、/auth/refresh、/auth/logout；GET/PATCH /me | 注册分配LEARNER；刷新令牌轮换；注销撤销链 |
| 角色 | POST /me/role-applications；GET/PATCH /admin/role-applications/{id} | 仅贡献资格可申请；审核、管理员需单独授权 |
| 文件资料 | POST /files；POST/GET /source-documents；GET /source-documents/{id}/segments | 文件访问校验资料权限，禁止任意路径参数 |
| 草稿版本 | POST /resources/{id}/revisions；PATCH /revisions/{id} | 仅本人DRAFT允许编辑；提交后冻结 |
| 审核 | POST /revisions/{id}/submit；GET /reviews；POST /reviews/{id}/decisions | 携带lockVersion；自审拒绝；重复决策返回原结果或409 |
| 知识 | GET /knowledge；GET /knowledge/{id}；GET /knowledge/{id}/relations | 公开请求只读取有效正式版本 |
| 方剂 | POST /formulas/parse；POST /formulas/match；POST /formulas/compare | 用户先确认解析；匹配分不是诊断或正确概率 |
| 问答 | POST/GET /qa/sessions；POST /qa/sessions/{id}/messages；GET /qa/messages/{id}/stream；POST /qa/messages/{id}/stop | 本人鉴权；消息幂等键；SSE独立终态 |
| 学习 | GET /topics；POST /topics/{id}/enrollments；GET /me/enrollments；PUT /enrollments/{id}/cards/{cardId}/completion | 目录版本固定；完成标记可重复调用 |
| 测验 | POST /assessments/{id}/attempts；PUT /attempts/{id}/answers/{itemId}；POST /attempts/{id}/submit | 仅本人IN_PROGRESS；服务端评分；重交不重复累加 |
| 错题 | GET /me/wrong-questions；POST /me/wrong-question-practices | 复习形成新作答，不覆盖旧成绩 |
| 推荐 | GET /recommendations；POST /recommendation-items/{id}/exposures；POST /recommendation-items/{id}/clicks | 真实可见曝光；点击校验归属并幂等 |
| 社区 | POST/GET /posts；POST /articles；POST /posts/{id}/comments；POST /reports | 讨论与文章策略不同，文章审核后公开 |
| 共用 | PUT/DELETE /resources/{id}/bookmark；GET /me/notifications | 收藏和点赞重复请求不重复计数 |
| 运维 | GET /admin/jobs；POST /admin/jobs/{id}/retries；GET /admin/audit-logs | 只允许技术管理权限，日志脱敏 |

Python接口：POST /internal/v1/parse、/extract、/indexes/upsert、/indexes/remove、/retrieve、/generate-stream。每次调用携带短期内部凭据、traceId和明确对象版本。Java负责鉴权、MySQL状态和任务回写，Python负责输出结构化结果及检索数据写入。返回证据必须包含revisionId/segmentId、channel、metricName、rawMetric及原文，不接受模型自行编造来源ID。

错误分类：VALIDATION_ERROR→400、UNAUTHENTICATED→401、FORBIDDEN→403、NOT_FOUND→404、STATE_CONFLICT→409、RATE_LIMITED→429、UPSTREAM_UNAVAILABLE→503。内部堆栈不返回浏览器。

异步任务领取和回写：领取生成runToken；回写携带jobId、runToken及artifactGeneration。Java以WHERE id=? AND run_token=? AND status='RUNNING'更新，受影响行数0表示过期回写，不能覆盖新执行结果。
