# 五个手测账号登录排查（2026-11-24）

- 目标：用户反馈原网站五个 demo 账号无法登录；用户确认使用 `http://localhost:5173`。
- 范围：按 targeted-debug 定位并按 code-review 自审；不重置密码、不改账号状态/角色、不迁移数据库、不重启服务、不提交或推送。
- 基线：main `1cdbceb1`；检查脚本与日志位于隔离工作区 runtime，不计入业务代码。
- 实际环境：5173→原项目8080/隔离库53793；5174→第三阶段隔离工作区8081/隔离库17629。两个站点同时监听，进程与数据库记录已核对。
- 确定事实：原库存在 demo_learner、demo_contributor、demo_contrib2、demo_reviewer、demo_admin，全部ACTIVE，角色与用户清单一致。使用用户给定密码，经过5173前端代理调用五次登录，均HTTP200。5174没有这些账号，登录均401；该差异与本次用户确认的5173异常无直接因果证据。
- 浏览器最小回归：真实Edge、五个独立新上下文，各自从5173/login填表并点击登录，跳转/account；刷新后auth/refresh均200，仍在/account，均无pageerror。检查脚本 `runtime/probe-demo-browser.cjs`，日志 `runtime/demo-login-browser.log`，五条结果 `runtime/demo-login-browser-result.json`，截图 `runtime/screenshots/login-demo_*-5173.png`。这些路径均属于 `C:/Users/86150/.codex/worktrees/stage-three-batch-one/tcm-learning-platform`。
- 排查脚本第一次错误地等待首页，而应用正常默认跳转/account；修正测试预期后五例通过。这是检查脚本问题，不是网站登录失败的复现。
- 代码复核：AuthView直接提交用户名/密码，登录不套注册密码复杂度；IdentityService检查用户名、BCrypt及ACTIVE后签发会话；客户端auth/login的401不触发业务请求刷新重放。未发现与当前反馈有证据关联的可操作缺陷，无生产代码差异。
- 未验证：用户现有浏览器配置、实际失败提示/请求、输入差异或扩展干预。新浏览器成功不能证明用户原浏览器未发生异常，也不代表整个个人中心业务回归完成。
- 下一步：取得用户失败时具体提示、账号和必要的脱敏请求结果后，只复现对应条件。目前根因未确定，不以密码错误、缓存问题或跨环境会话冲突冒充已确认原因。
