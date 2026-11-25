# 社区不可用排查（2026-11-24）

- 目标：恢复原站点 `http://localhost:5173/community`；使用 targeted-debug 和 code-review。
- 基线：main `1cdbceb1`。开始前 README/findings 及工作流入口已有修改/未跟踪文件，保留原样；不提交、不推送。
- 截图：分区、帖子列表均显示服务不可用；追踪号 `fc969c9c-c208-4f3e-b359-4ab68e24c267`、`29fe51d7-95c0-4b6a-bde7-4f973525ef49`。旧日志未记录 traceId 和完整栈，因此不能按追踪号精确关联业务栈帧。

## 证据与确定原因

- 8080 的实际进程 34652 启动于 11 月 24 日 10:19，加载原项目 `backend/target/tcm-platform-0.1.0.jar`；该 JAR 文件时间为 11 月 19 日 14:53。启动日志只验证 V1–V6，原库当前版本为 6。
- 5173 的 Vite 代理指向 8080；当前源码已有 CommunityController 的分区/帖子路由、V7/V8 Java 迁移、V9 SQL 迁移。8081/5174 为另一个隔离工作区，不作为原站点修复结果。
- 11 月 24 日 15:44–15:46（日志 UTC 07:44–07:46）旧后端连续记录 `NoResourceFoundException`。错误发生在路由分派阶段，没有进入社区业务函数；旧异常处理器将缺失路由转成通用 500。当前源码已存在标准 404 处理，无需重复改写。
- `node runtime/probe-community-debug.cjs before`：真实 Edge 登录 demo_learner 后进入社区，`/api/v1/community/sections`、`/api/v1/posts` 均 HTTP500，两处服务不可用，无 pageerror。JSON 和截图为 `runtime/community-debug-before.json/.png`。
- 确定原因：源码合入后原站点后端运行产物未同步，形成新版前端与旧版后端混用；不是据旧验收记录推断社区未实现。
- 旧 JAR 内容清单核对：CommunityController、V7/V8 类均不存在；重新打包的 JAR 包含这些类。
- 第二个确定原因：更新后端后分区/帖子请求均恢复 200，但发布入口仍无法导航。`runtime/community-debug-missing-deps.log` 首个失败点为 Vite 无法解析 `src/modules/community/markdown.ts:1` 的 `dompurify`；原 node_modules 中 dompurify、markdown-it 均不存在，package.json/lock 已声明新依赖。依赖安装也未随合并同步。

## 修复与验收计划

1. 预览 `scripts/test-changed.ps1 -PlanOnly`：当前只有既存文档/工作流差异，无业务测试选择；本次运行产物与数据库升级额外补 `CommunityMigrationTest,CommunityWorkflowIntegrationTest`。
2. 原库升级前已保存 ignored runtime 备份 `runtime/community-debug-before-upgrade.sql`；原库版本 6、用户总数 388。备份含私人数据，不输出、不提交。
3. 运行 `scripts/test-backend.ps1 -TestPattern 'CommunityMigrationTest,CommunityWorkflowIntegrationTest'`；迁移兼容测试独占临时 schema，业务测试事务回滚。日志 `runtime/community-debug-backend.log`。
4. 测试通过后打包并通过 `scripts/dev.ps1` 停启原项目服务，沿用原数据库、密钥和端口；不操作另一工作区。
5. 真实 Edge 复验分区、帖子列表、发布讨论编辑器和刷新会话。浏览器不向原库发布合成帖子；讨论/评论写入与版本隔离由数据库回归验证。

## 当前结果

- `scripts/test-backend.ps1 -TestPattern 'CommunityMigrationTest,CommunityWorkflowIntegrationTest'` 退出0：6项迁移、7项社区流程，共13项通过，无失败/异常/跳过，耗时9分29秒。包括旧V6数据升级、非法数据预检、公开指针、成员权限、讨论及评论版本/重放等。
- 原库已从V6升到V9；升级前后用户388条，身份/密码/状态与正文版本标题/正文/审核状态的计数及聚合校验指纹一致。指纹文件 `runtime/community-debug-data-before.tsv`、`runtime/community-debug-data-after.tsv`；不是逐行密码明文导出。
- 首次 `mvn -B -DskipTests package` 退出1，Windows旧Java进程占用JAR导致repackage无法重命名；日志 `runtime/community-debug-package.log`。条件改变后通过dev.ps1停止原项目，再次打包退出0，日志 `runtime/community-debug-package-after-stop.log`。未原样重试、未重跑已通过且无变化的后端测试。
- 通过dev.ps1停启原项目并确认新JAR/9项迁移；首次浏览器复验列表、筛选及分区详情200，发布编辑器因缺依赖而超时，不能把这次部分通过作为最终验收。
- 停止原服务后 `npm ci --no-audit --no-fund` 退出0，按锁文件安装239个包；package.json/lock无差异。日志 `runtime/community-debug-npm-ci.log`。
- `npm test` 退出0，18文件101项通过；`npm run build` 退出0，vue-tsc及Vite构建通过。日志 `runtime/community-debug-frontend-test.log`、`runtime/community-debug-frontend-build.log`。仅有既有whatwg-encoding弃用提示。
- 最终 `scripts/dev.ps1 start` 退出0，日志 `runtime/community-debug-final-start.log`。新后端PID42592监听8080，前端5173、AI8000正常启动；启动验证9项迁移，库版本9。
- `node runtime/probe-community-debug.cjs after` 退出0：真实Edge登录demo_learner，社区分区/列表、应用筛选、进入分区详情、返回社区、打开发布讨论编辑器、刷新后重新加载表单均通过。采集的23条社区响应全部200，服务不可用提示0，pageerror为空；最终URL为 `/community/posts/new`。结果 `runtime/community-debug-after.json`，截图 `runtime/community-debug-after.png`、`runtime/community-debug-editor-after.png`。
- 最终脚本补充了等待分区导航可见及编辑器正文表单可见的断言，避免仅以networkidle认为组件渲染完成；再次通过后才作为完整的本次浏览器验收。

## 本次差异审查与边界

- code-review先核对diff --stat，再核对既有README/findings差异；新增任务记录和DEVELOPMENT_STATE本任务段单独检查。业务源码、迁移、package.json/lock均无本次差异，既存工作流修改保留，git diff --check通过。
- 对根因相关调用链检查了Vite代理、社区前端API/路由、CommunityController、GlobalExceptionHandler和V7/V8迁移入口；当前缺失路由已有标准404处理，因此没有再改异常映射或权限来掩盖旧产物问题。
- 未发现本次修复引入的可操作代码问题；这是运行环境同步修复，不宣称对整个社区源码做全面审计。没有API/表/模块入口变化，无需变更契约或测试选择策略。
- 业务数据库测试覆盖讨论/评论写入与版本隔离；本次浏览器仅对原站点做读取和编辑器加载，不发布合成帖子或修改既有账号。未执行全量E2E、所有管理/文章额度流程或完整第三阶段验收。
- 旧日志中的traceId不可精确回溯，原始异常以同动作真实浏览器复现及JAR内容差异补证。备份、JAR和可能含私人数据的runtime文件不输出、不提交。
- 使用方法：继续使用 `http://localhost:5173/community`，刷新页面后即可进入；若浏览器保留重启前的请求失败状态，刷新会重新读取当前服务。以后合入含新后端/依赖的代码后，需同步安装锁文件依赖、停旧进程再打包、用dev.ps1启动，并验收对应业务页面。
