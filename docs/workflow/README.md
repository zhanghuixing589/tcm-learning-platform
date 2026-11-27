# 标准开发工作流

目标：用稳定入口、局部证据和一次必要验证减少重复上下文；不以缩小测试牺牲权限、数据状态和跨模块完整性。本工作流只增加开发基础设施，不改变业务逻辑或既有阶段设计。

## 文档职责与渐进加载

| 入口 | 何时读取 / 谁负责更新 |
|---|---|
| AGENTS.md | 项目短规则，自动或首次进入读取；工作方式改变才更新 |
| DEVELOPMENT_STATE.md | 恢复/交接读取；任务负责人按里程碑和会话结束更新 |
| MODULE_INDEX.md | 定位职责/路径/表/API/测试；入口或依赖变更时更新对应行 |
| docs/modules/*.md | 只读任务模块；模块规则/状态变化时更新，保留原详细设计引用 |
| PRODUCT.md / DESIGN.md | 产品范围 / 视觉任务按需读取；保留现有有效约定 |
| DECISIONS.md | 有方案冲突/选择时读取相关条目；决定、撤销或替代时更新 |
| contracts/README.md | 接口变更或联调才读相关章节；与 Controller/DTO/消费者同一次修改 |
| 旧 task_plan.md / findings.md / progress.md、docs/07、09、10 | 历史追溯与验收快照，不作为当前任务进度，不每轮全读 |

读取顺序：短规则 → 当前状态（续做时）→ 索引对应行 → 一个模块文档 → 当前函数、直接消费者和关联规则。长文用标题/符号定位后读局部。不要复制完整数据库字典、阶段 spec 或接口总表进每个 Skill。模块文档标记已实现/待实现，细节仍以原设计与当前代码交叉核对。

上下文已有效加载且文件未变时直接复用；可保存路径、符号、内容哈希和一句结论。新会话缺少函数细节时读取局部，不能把“摘要说读过”当成代码仍在上下文。跨会话至少核对 HEAD、相关工作区 diff 和环境变化。

## 按任务复杂度执行

1. 确认目标、修改边界和验收条件。能从已有需求/设计得出的例行细节直接实施；会改变业务规则的歧义才向用户澄清。
2. 按索引加载必需上下文，保护开始前已有修改。
3. 小任务直接列简短方案；跨模块、权限、迁移、异步任务写清依赖、状态变化、兼容及验证计划。遵循 docs/05 的业务阶段顺序。
4. 实现限定范围；修 bug 先取得最小复现，新增行为补有意义的行为验证，不为纯文案堆测试。
5. 预览增量计划，合并重复套件后运行；失败先定位根因，不连续重复整套测试。
6. 审查本次差异和必要调用者；核对业务、权限、状态及契约/索引/设计同步。
7. 记录准确完成状态、测试证据和下一步。全部必要验收完成才标记完成；未跑的浏览器或专项测试明确留下缺口。

简单文案可缩为定位→修改→检查显示/链接→状态记录，不强制设计文件、全套测试或多代理。复杂任务可使用独立任务记录，但不同时维护多个相同进度源。

## 六个 Skill

| Skill | 触发场景 | 首个输出 |
|---|---|---|
| project-context | 不清楚模块、规则、代码入口或实际状态 | 必需入口与当前范围 |
| api-contract | 改路由、DTO、类型、错误、权限或内部回写 | 契约变化和消费者清单 |
| targeted-debug | 日志异常、traceId、HTTP/构建/测试失败 | 最小复现与一个待验证假设 |
| incremental-test | 为代码/配置/迁移/契约选择验证 | 去重后的测试计划及原因 |
| session-resume | 跨会话续做或交接、记录里程碑 | 代码与记录差异、下一条动作 |
| code-review | 本次变更交付前或用户要求审查 | 有证据的问题或残余验证缺口 |

项目技能位于 `.agents/skills/<name>/SKILL.md`。按任务调用 `$project-context` 等；客户端尚未发现新技能时可明确要求读取该路径，重新进入项目后再检查发现情况。只加载适用技能，日常无需读取此表后的所有参考。

## 测试策略与命令

在项目根目录 PowerShell 运行；参数支持 `-JavaHome`、`-Maven`、`-Python`，默认沿用既有本机开发路径。脚本不安装依赖，不修改 dev.ps1 的进程/数据库约定。

```powershell
# 环境诊断：只检查，不启动、不安装、不创建管理员
.\scripts\check-env.ps1

# 仅预览：包含 HEAD 以来工作区、暂存区、未跟踪文件
.\scripts\test-changed.ps1 -PlanOnly

# 分支范围：merge-base 到 HEAD 的已提交改动 + 上述本地改动
.\scripts\test-changed.ps1 -Base main -PlanOnly
.\scripts\test-changed.ps1 -Base main

# 运行合并后的必要计划
.\scripts\test-changed.ps1

# 指定模块；默认纳入模块相关层和跨模块消费者
.\scripts\test-module.ps1 -Module knowledge -PlanOnly
.\scripts\test-module.ps1 -Module source,knowledge

# 只核验前端组件时可定向；不能用它替代涉及后端的完整计划
.\scripts\test-module.ps1 -Module account -Layer frontend

# 修改测试策略后验证选择逻辑，不启动真实业务测试
.\scripts\tests\workflow.Tests.ps1
```

`test-changed` 不按文件名匹配同名测试。Test-Policy.ps1 是唯一选择实现，两个入口共享 Invoke-TestPlan 执行和证据输出：

| 变化 | 自动选择 |
|---|---|
| 个人中心 / 来源 / 审核 / 知识 Java | 共享资源回归组：IdentityIntegrationTest + PersonalCenter + SourceWorkflow + KnowledgeWorkflow；前端相关模块与 client/access；来源/审核/知识还包含 AI 测试 |
| identity / common / operations、迁移、全局配置、后端依赖、contracts、未知路径 | 保守选择全部 Java 测试、前端单元/组件和类型构建、AI 测试 |
| 前端 account / knowledge 普通局部改动 | 相关依赖组的前端测试与构建；auth/passwordPolicy/shared API/store/router 额外回归服务端安全边界 |
| 其他前端 / 前端依赖锁 | 前端全单元/组件与类型构建；仍需审查是否改变接口或安全行为并扩大 |
| AI 代码/依赖 | AI 测试及 Java/前端消费者回归 |
| Java 测试源码新增/修改/移动 | 全 Java 测试，避免新类被既有固定类列表漏选 |
| community / qa / learning / recommendation | 既有基线回归 + 显式报告尚无专项套件；不能据此验收新业务 |
| 纯已知 Markdown 文档/Skill | 不跑业务套件；检查链接、事实、规则冲突。contracts Markdown 属于契约变更，按保守规则处理 |
| 本工作流脚本/自检 | 执行 workflow.Tests.ps1；其他脚本未知影响按保守规则处理 |

Java 复用 `test-backend.ps1 -TestPattern`：真实隔离 MySQL、测试编译及 Surefire；默认不是重新打包 JAR。要运行新后端应用时再执行既有 `test-backend.ps1` 打包，然后按 dev.ps1 管理进程，避免把旧 JAR 当新实现。脚本会启动缺失状态的隔离库；已存在状态却连接失败时先诊断，不自动停库或清数据。

前端每个计划只调用一次 Vitest，再执行 npm run build（包含 vue-tsc）；AI 使用项目 Python3.11 执行 pytest。选择规则刻意保守：共享 SQL/公开可见性不一定有 Java import，未知依赖不能据“没找到 import”跳过。

实际执行保存 `runtime/workflow/<run-id>/plan.json`、`result.json` 及各步骤日志；有失败立即退出并保留失败步骤，剩余步骤不冒充执行成功。`-PlanOnly` 不运行测试、不启动服务。没有选中测试记为 no-automated-tests-selected，成功仅称 selected-checks-passed，均不表示完整业务已验收。

复用测试结果需核对相关源码、传递依赖、迁移、锁文件、环境配置、工具版本、测试输入和命令相同；记录结果目录以及核对结论。脚本不提供仅按 HEAD、文件名或时间戳自动跳过测试的缓存。依赖相同且已有当前有效结果时，不因进入审查阶段再跑一次。

## 开发端口与多工作区

启动统一使用 `scripts/dev.ps1`。`scripts/Dev-Ports.ps1` 供启动、环境导入及check-env共用：主工作区默认前端5177、后端8084、AI8004，均绑定127.0.0.1；显式参数优先于本工作区 `runtime/dev-ports.json`，最后才使用主站默认值。独立Git worktree没有本地配置且未显式传入全部三个端口时拒绝启动，避免误用主站配置。端口必须为1024—65535的不同整数；启动前检查实际IPv4回环占用并报告端口与监听PID，不自动换号或结束占用者。

同一工作区的并发启动由runtime/dev-start.lock文件独占锁串行保护；锁随启动进程退出释放，文件存在不代表仍持锁。成功前检查本次启动进程仍存活，并核对前端HTTP、后端readiness及AI健康，避免误把另一个进程的监听当成本次启动成功。

每个独立工作区在其runtime目录保存配置（不提交Git），例如stage-three-batch-one：

```json
{"FrontendPort":15178,"BackendPort":18085,"AiPort":18005}
```

保存后在该目录运行 `./scripts/dev.ps1 start`；也可显式传入 `-FrontendPort 15178 -BackendPort 18085 -AiPort 18005`。新工作区需另选未占用端口；配置不是全局端口预留，实际占用检查为准。停止只管理对应目录记录的进程；历史PID记录须先核对启动时间，不能根据PID直接结束进程。当前已运行服务不因编辑配置而重启；下次启动按配置生效。

Java环境导入固定设置SERVER_ADDRESS=127.0.0.1，避免与Docker/WSL的IPv6监听混用；代理BACKEND_URL、AI回调、允许来源随所选端口同步。Vite独立启动的主站默认值同步为5177/8084与回环绑定；直接运行npm/Vite不读取本地profile，需要自定义端口时使用上述统一启动入口。既有隔离工作区的Vite默认值也同步为15178/18085。MinIO继续使用宿主机19000/19001→容器9000/9001，应用端点为127.0.0.1:19000，不能改成宿主机9000。

## E2E 与安全边界

不默认启动应用或跑全 E2E。认证/刷新/路由行为、上传权限、审核发布与个人列表的跨端变化，按验收条件选择真实浏览器场景；自动测试成功不能免除此类必要验收。现有入口为 account.spec.ts、personal-center.spec.ts、stage-two.spec.ts，查看相关 test 标题后选择 `--grep` 或文件。

```powershell
# 需要真实浏览器验收时：先确认应用与隔离测试账号条件
# 启动/管理员准备按 docs/08，已有服务不要重复启动
Push-Location frontend
npx playwright test e2e/stage-two.spec.ts
Pop-Location
```

未准备场景或账号时记为阻塞，不创建默认管理员、不重置已有账号、不以 mock 测试冒充真实验收。脚本只提出 manual 检查，不替用户完成依赖业务语义的场景选择。

## 日常示例

- **修改知识详情空状态**：project-context 定位 KnowledgeDetailView，读取 DESIGN 对应规范；调整组件，按前端计划验证并检查空状态。未变更接口，不重新分析数据库或运行全 E2E。
- **来源停用后收藏仍可见**：targeted-debug 用 traceId 和最小对象链复现；查看 SourceService、PublishedKnowledge、PersonalCenterService；修复后使用共享组回归及必要的来源→知识→收藏真实链路。根因与剩余问题记状态。
- **新增接口字段**：api-contract 查 Controller/DTO、knowledge/api.ts 和消费者，明确兼容/空值及权限；同步契约、实现与行为验证；test-changed 因契约变更扩大回归，完成后 code-review 核对一致性。
- **第二天继续未完成任务**：session-resume 读取状态，核对 HEAD 与未提交差异，只恢复下一步所需函数。若昨日相关测试输入/代码/环境未变则保留有效证据；修改依赖后重跑受影响部分。

## 持续维护与审查

新增/移动模块入口、表或测试时，同一次变更更新 MODULE_INDEX、对应模块文档和 Test-Policy。业务决定先更新 DECISIONS 的确认状态；设计未落地时模块文档继续标记待实现。不要用“更新日期”批量掩盖未核实内容。

结束任务核对：需求边界、已有修改保护、相关调用者、契约与状态一致、必要测试及证据、遗留问题与下一条动作。此流程依赖开发者执行和审查，目前未配置强制 CI 门禁，也不承诺未测量的额度节省比例。

## 本次文件清单

新增27份：

- 根入口：[AGENTS.md](../../AGENTS.md)、[MODULE_INDEX.md](../../MODULE_INDEX.md)、[DEVELOPMENT_STATE.md](../../DEVELOPMENT_STATE.md)、[DECISIONS.md](../../DECISIONS.md)。
- 模块设计：[identity.md](../modules/identity.md)、[account.md](../modules/account.md)、[source.md](../modules/source.md)、[content.md](../modules/content.md)、[knowledge.md](../modules/knowledge.md)、[platform.md](../modules/platform.md)、[community.md](../modules/community.md)、[qa.md](../modules/qa.md)、[learning.md](../modules/learning.md)、[recommendation.md](../modules/recommendation.md)。
- 技能：[project-context](../../.agents/skills/project-context/SKILL.md)、[api-contract](../../.agents/skills/api-contract/SKILL.md)、[targeted-debug](../../.agents/skills/targeted-debug/SKILL.md)、[incremental-test](../../.agents/skills/incremental-test/SKILL.md)、[session-resume](../../.agents/skills/session-resume/SKILL.md)、[code-review](../../.agents/skills/code-review/SKILL.md)。
- 脚本：[test-module.ps1](../../scripts/test-module.ps1)、[test-changed.ps1](../../scripts/test-changed.ps1)、[check-env.ps1](../../scripts/check-env.ps1)、[Test-Policy.ps1](../../scripts/Test-Policy.ps1)、[Invoke-TestPlan.ps1](../../scripts/Invoke-TestPlan.ps1)、[workflow.Tests.ps1](../../scripts/tests/workflow.Tests.ps1)。
- 指南：本文件 docs/workflow/README.md。

修改2份：[README.md](../../README.md) 增加工作流入口；[findings.md](../../findings.md) 标注历史快照。保留 PRODUCT.md、DESIGN.md、原阶段设计、已有业务改动和原开发脚本。
