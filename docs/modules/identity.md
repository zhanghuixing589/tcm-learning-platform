# 身份与权限

状态：已实现；当前入口与测试见 MODULE_INDEX 的 identity 行。详细既有依据：`docs/07-第一阶段验收.md`、`contracts/README.md`；界面遵循 PRODUCT / DESIGN。

## 职责与实现

- AuthController → IdentityService / TokenService / IdentityMapper：注册、登录、刷新轮换和退出。
- AccountController 处理本人资料/贡献资格申请，AdminIdentityController 处理用户、角色及资格审批。前端 AuthView、ApplicationsView、modules/admin 与 session store 消费这些接口。
- 主要表为 sys_users、角色/权限关联、sys_role_applications、auth_refresh_tokens；会话绝对期限由 V3 扩展，字段以当前迁移及 Mapper 为准。
- 资格申请：ApplicationsView/RoleApplicationDetails提供本人记录详情、明确申请人/编号和状态刷新；AdminApplicationsView复用详情并按ALL/PENDING/PROCESSED服务端筛选、同步分页总数与审批后页码。筛选参数定义在IdentityDtos.ApplicationFilter；Mapper的APPLICATION_WHERE由列表与count共用。ApplicationsView.test覆盖详情、换号清理、筛选页码及异步旧结果；IdentityIntegrationTest覆盖真实SQL筛选/总数/归属/无权/无效参数。
- 管理列表可保留本人申请供只读查看；AdminApplicationsView按userId与session.user.id识别本人，禁用本人待处理记录的处理入口、显示其他管理员处理提示，详情也不提供自审动作，select/decide仍防御性校验。审批错误保留在审核弹窗内并滚动/聚焦提示、保留处理意见；AdminUsersView的角色保存错误同样在角色弹窗内展示。两页以类型明确的计算属性/格式化函数读取选中用户名、说明和状态。ApplicationsView.test覆盖本人禁用及两个弹窗的失败提示；e2e/applications.spec.ts包含合成只读列表/409/403界面响应验证与真实新申请审批回归，后端自审409由IdentityIntegrationTest专项验证。

## 必须保持的规则

注册分配 LEARNER；贡献资格可申请，REVIEWER/ADMIN 显式授权；管理员不自动具备专业审核权。密码规则查看 PasswordPolicy 与前端 passwordPolicy.ts，不能只调整页面提示。

短期 JWT 由内存客户端持有，刷新令牌以 HttpOnly Cookie 传输、数据库保存哈希并轮换；固定登录链最长30天。refresh/logout 校验 Origin 与 CSRF 请求头；禁用、轮换重放等涉及令牌即时失效，不能只检查 JWT 签名。

## 契约与回归

接口组：`/auth/{register,login,refresh,logout}`、`/me`、`/me/role-applications`、`/admin/users`、`/admin/role-applications`；精确方法/字段看三个 Controller 和 IdentityDtos。

修改此模块影响全部需要鉴权的业务。验证普通用户越权、他人对象、自身角色修改限制、刷新重放、边界到期、跨账号旧请求及双标签状态。Java 四个身份测试类、前端 client/access/passwordPolicy 测试是直接入口；浏览器按场景选择 account.spec.ts 与 personal-center.spec.ts。身份代码变更使用全业务自动回归计划，历史通过数不代替本次验证。
