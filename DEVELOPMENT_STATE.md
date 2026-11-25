# 开发状态

## 已完成：知识搜索框样式冲突修复（2026-11-25）

- 复现个人中心 → 知识中心的站内导航后，账号模块全局 `.library-search` 将同名知识搜索框覆盖为224px宽。知识目录与方剂比较页改用独立 `.knowledge-search`，恢复横向布局、输入框单层边框和按钮不换行；不修改搜索业务。
- Edge真实浏览器沿上述导航路径验证1440/1280/768/390四视口、两页面共8项通过，Enter搜索正常，无横向溢出或pageerror；相关Vitest两文件8项、vue-tsc/Vite构建及定向diff检查通过。证据与修改后截图：`runtime/knowledge-search-fix/`。本次涉及三个知识模块文件及本状态记录，未提交推送。

## 已完成：知识类型 / 类型专属目录 / 通用标签重构（2026-11-25）

- 用户授权本轮后端/数据库/管理端完整业务改造。已实现固定六类型、31个初始类型专属目录、跨类型通用标签；后台动态维护、乐观版本/权限/审计；前台横向目录、类型切换清目录和分页、全部隐藏、独立标签筛选及准确空态。编辑器、审核阅读、版本对照、标签检索及修订复制同步。
- V10已在主站和既有隔离库执行；旧目录只读归档，旧版本正文/分类指针不改写，后续稿件需选有效目录或未分类。类型CHECK/赋值触发器与保存/提交/批准有效性校验落地；停用不撤销已发布知识。新权限knowledge:taxonomy:manage默认仅ADMIN，不授予知识审核。契约见contracts/knowledge-taxonomy.md，决策K01。
- 迁移前完整备份及指纹保存在runtime/knowledge-taxonomy/；最终校验主站15个既有知识版本/3个旧目录、隔离28个版本/3个旧目录均保留。非空旧标签关系由专门迁移测试覆盖。未清理旧业务或验收数据，未commit/push。
- 全后端174项执行：172项首次通过，2处旧迁移数9断言改为10后原失败方法复验2项通过（backend-all.log、backend-count-checks.log）；新增分类4项及迁移兼容通过。首次专项16项通过。后端package成功，两个环境部署JAR哈希一致。
- 全前端32文件167项及后续编辑/配置专项7文件18项通过，vue-tsc/Vite最终通过；AI24项、工作流32断言通过。原知识E2E和分类管理/标签/编辑保存E2E各1条通过，四视口无页面溢出/pageerror=0。打包及状态恢复后主站只读13项再次通过。
- 两套服务最终均运行新JAR：主站5173/8080、隔离5175/8082。任务启动脚本曾因statePath变量与环境导入冲突误写本地MySQL状态文件，已恢复原配置及服务记录，以原凭据重新认证（未重置密码），幂等DB启动和最终指纹检查通过；脚本使用独立变量名，临时认证文件已删除。产品/数据库内容未受此脚本错误影响，详见本轮记录。
- 新文件/接口/数据库/测试映射及PRODUCT/DESIGN/DECISIONS/字典/模块/契约已同步。完整文件清单、失败与复验边界、迁移说明、截图见[本轮交付](docs/workflow/tasks/2026-11-25-knowledge-taxonomy.md)。本次授权范围已完成，后续按具体反馈调整；推荐/RAG仍未实现，不将标签基础描述为这些能力已完成。

## 已完成：知识中心第二轮精细化（2026-11-25）

- 在 main / 1cdbceb1 的未提交工作区继续，保留前序所有修改。范围仅知识目录/详情/比较及其必要组件；不改首页社区、后端、接口、数据库和审核权限。
- 已实现：浅白卡片/核心属性/类型与目录分离/导航线性图标；用户提供黄芪与陈皮图片按HERB规范名严格匹配；来源抽取KnowledgeEvidence；阅读目录当前章节、版本与质量维护；比较成功自动定位/候选收起/调整恢复、三类组成标识/仅差异及双向sticky表格。图片只用于匹配条目的参考图，不宣称为版本审核附件。
- 新增文件：KnowledgeEvidence.vue及.test.ts、KnowledgeTypeIcon.vue、herbImages.ts、assets/herbs/{huangqi,chenpi}.png；另修改现有目录/详情/比较组件、library.css及相关测试。增量计划runtime/knowledge-polish/test-plan.log。
- 隔离真实E2E1条/4步骤通过（runtime/knowledge-polish/e2e.log）；比较定位时“正在读取”提示移除造成43px偏移已修复，实测标题顶109.55px/导航底90px。补充Edge28项及边界32项通过，含四视口、有图/无图、长名称/50段正文/12条来源、版本对照、纠错失败保留与实际重试、比较定位/调整/差异/双向sticky和加载失败恢复；无页面溢出、pageerror=0。有图和长内容使用明确标注的浏览器响应夹具，未写主站数据。
- 本轮全前端31文件163项通过（tests-final.log）；最后重复比较失败恢复候选的修复后，3文件9项及vue-tsc/Vite通过（tests-delivery.log、build-delivery.log）。初期测试/脚本失败及原因保留，不计为通过。无后端/接口/依赖/数据库/审核权限变动；未清理旧数据，未提交推送。
- 模块索引已同步；[完整本轮文件清单、测试边界、问题原因、三类页面截图与接口限制](docs/workflow/tasks/2026-11-25-knowledge-polish.md)。授权范围内已完成，后续仅按用户反馈定向调整；主站5173/8080和隔离5175/8082继续运行。此前第一轮159项作为历史记录保留，不混同本轮证据。

## 已完成：知识中心及方剂比较专项重构（2026-11-25）

- 知识目录突出真实搜索，保留全部及六类型单套导航、后台共享分类和分页；按实际药材/方剂/概念/其他类型字段分别展示，无图不造图。详情保留收藏、版本、纠错、来源与关联，动态章节导航和异步来源锚点避让；不可见版本安全提示。
- 唯一账号隔离方剂选择：目录/详情加入、跨搜索分页保留、2–3项限制、移除替换/清空；比较沿原接口，真实药材ID区分共同与差异，保留原用量依据，手机横向滚动固定维度。刷新清空有明确说明。
- 最终全前端30文件159项通过（runtime/knowledge-library/tests-final.log）；最后来源锚点补验2文件8项及vue-tsc/Vite通过（anchor-tests.log、build-delivery.log）。首次高并行超时证据保留，未修改社区测试掩盖失败。
- 独立5175真实E2E1条/4步骤通过：普通注册/赋权/来源审核/知识审核后13条正式验收记录，含六类型及2/3方比较、收藏与普通读者越权拒绝；主站保持真实空库。补充Edge42项通过、四视口无横向溢出/pageerror=0；另补验来源hash/键盘焦点/对比度通过。
- 无后端/接口契约/数据库/依赖/认证/审核规则修改，不改首页社区，不改写或清理原数据，未提交推送。完整本次文件、截图、模型不支持项及测试边界见[专项交付](docs/workflow/tasks/2026-11-25-knowledge-library.md)。原5173/8080及独立5175/8082服务继续运行。

## 已完成：社区第三轮精细化（2026-11-25）

- 后续按用户截图微调：移除编辑器底部“Markdown正文·预览自动更新”说明及讨论字数前的格式名称，仅保留真实字数/额度；MarkdownEditor与App共4项针对性测试通过（runtime/community-round-three/remove-format-tests.log），不改变格式、编辑、保存或预览逻辑。

- 保留第二版设计，创作区改为单层写作画布、图标工具栏/自适应正文/更清楚的模式切换；讨论呈现实际本地草稿状态，文章顶部复用服务器保存并区分未保存/保存中/成功/失败，工具栏避让实际保存条高度。
- 文献顶部添加、可折叠滚动列表与删除确认，原编号/字段不变；大量分区检索、讨论列表收藏与详情一致、同日期去重、评论提示/回复间距。用户ID去重、按实际标题显示目录等已满足逻辑直接保留。
- 全前端26文件147项、vue-tsc/Vite通过（runtime/workflow/20261125-105746-380-5596d62e）；最后纯样式补验构建通过（runtime/community-round-three/build-final.log）。独立真实E2E含文献/顶部保存/刷新/审核/评论回复折叠通过；浏览器27项通过、四视口无横向溢出、pageerror=0。
- 无后端/API/迁移/依赖/权限/审核/Design System变更；未改写或清理原有测试数据，未提交推送。原5173/8080及独立验收5175/8082继续运行。完整范围/截图/失败与未验证边界见[第三轮交付](docs/workflow/tasks/2026-11-25-community-round-three.md)。

## 已完成：社区完整流程第二轮优化（2026-11-25）

- 保留已确认视觉，完成筛选去重/自动排序/分页失败正确重试、右栏去重与无封面文字排版；新增共享MarkdownEditor与CommunityGlyph、讨论本地草稿；文章复用服务器草稿/审核，目录高亮和导航/Skip Link/焦点避让完善。导航“长图中段”经实测为滚动截图伪影，不改z-index；摘要已有解析器，本轮补回归、统一实时预览与正式渲染。
- 业务范围仅社区页面、文章编辑器及必要公共导航/焦点；保留用户此前改动，无后端/API/迁移/依赖/角色规则改动，未提交推送。新模块/组件/测试关系已同步索引及模块文档。
- 最终25文件137测试及vue-tsc/Vite通过（runtime/workflow/20261125-103141-888-317d4b97）；最后仅补工具栏段落代码块/空首行边界，专项8项与最终构建通过（runtime/community-round-two/final-tool-tests.log、final-source-build.log）。
- 独立5175环境真实E2E1条/7步骤通过，含发布、草稿恢复、文章审核、互动评论与成员加入退出；浏览器35项状态检查及18项最终补验通过，四视口无横向溢出，pageerror=0。原5173/8080及原数据未改写或清理，独立验收服务仍运行以便复核。
- 删除线/图片上传受当前服务端排版策略约束，明确禁用；Post DTO无真实封面字段，采用无图布局。未运行后端/AI全量或全阶段三E2E，早期失败均保留而不计作通过。
- [完整交付、原因、文件清单、截图和验证边界](docs/workflow/tasks/2026-11-25-community-round-two.md)。本轮授权实施完成，后续按用户反馈定向调整，不扩大为新业务或全站重构。

## 已完成：首页第二版细节定稿（2026-11-25）

- 用户正式确认第二版为全站视觉基准，已记录 DESIGN.md；仅调整搜索主次按钮、复用社区摘要/头像/互动规则、移动辅助字14px，保留布局与所有真实路由、权限、空状态和功能预告。
- 针对性3文件19测试、vue-tsc/Vite构建通过；Edge18项检查通过（四视口、真实空知识/社区、有知识响应夹具、格式边界、整区点击与焦点、搜索和内容跳转），pageerror=0；辅助文字对比度5.12–5.63:1。
- 证据与截图 runtime/home-polish/，完整边界见[细节交付记录](docs/workflow/tasks/2026-11-25-home-polish.md)。复用原5173/8080，未改后端/依赖/账户，无外部调研，未提交推送。后续仅按具体反馈定向调整，不再次整体重构首页。

## 已完成：首页第二轮 UI/UX 优化（2026-11-25）

- 保留第一轮设计，桌面 Hero 620→约492px（减少20.6%）；新增真实搜索、整块学习入口与权限相关共建入口。知识为空紧凑显示，社区自适应排版，两区独立加载/重试并丢弃旧会话响应。用户确认中医概念使用 CONCEPT，未开放功能统一紧凑预告。
- 本轮业务修改仅 HomeView/HomeContent/home.css，新增 HomeView.test 与增补 HomeContent.test；保留所有既有修改，无 API/依赖/后端/认证/导航变更。同步模块入口，未提交推送。
- 最终全前端21文件121测试、vue-tsc/Vite构建退出0，证据 runtime/workflow/20261125-093504-314-249fdfdb。Edge32项通过，四视口三状态、搜索/导航/内容/资格路由已验，pageerror=0。脚本早期错误保留失败证据，未计作通过。
- 真实知识仍无已发布记录，有知识状态用浏览器响应夹具验证，未写库；接口无封面/来源名称，采用无图排版及详情来源入口。未运行后端/AI、全量 E2E 或真实知识发布写入链。
- 复用 dev.ps1 管理的运行服务 http://127.0.0.1:5173/ 和8080；[完整交付与前后截图](docs/workflow/tasks/2026-11-25-home-round-two/acceptance.md)。本轮实施与验收已完成，后续按用户视觉反馈定向调整。

## 已完成：全站前端视觉重构（2026-11-24）

- 按用户要求先完成外部参考调研、真实站点截图及访问失败记录，再实施本草阅读馆方向。独立交付记录：[全站视觉重构交付与验证](docs/design/acceptance.md)；设计入口 DESIGN.md、docs/design/reference-analysis.md。
- 改造首页、全站导航、社区、知识目录/详情、个人中心、资格、贡献/审核/任务、后台及共享控件；新增tokens、门户样式、三个公共UI组件和真实内容首页组合。保留原接口/路由/权限/业务状态，原社区分页文案和已有工作流修改未覆盖。未提交推送。
- 本次19文件105项前端测试、vue-tsc/Vite通过（runtime/workflow/20261124-170825-289-25c410b1）；最后纯样式/aria修改后再次构建退出0（runtime/design-final-build.log）。真实Edge多角色/四尺寸检查、最终16项补验与10项独立夹具通过；最终零pageerror及API 5xx，具体失败修复与证据见交付记录。不把早期脚本错误和审核模板失败计作通过。
- 当前库无已发布知识记录：真实空状态已验，有内容目录/详情仅用浏览器独立夹具补验，未写数据库。个人知识库/RAG/测验/推荐依然未开放。未执行后端/AI、全量E2E或真实发布审批写入链。
- 入口 http://127.0.0.1:5173/；服务复用原5173/8080，无需重启。后续内容发布或新增业务单独按原阶段推进，不以本次视觉验收代替业务验收。


## 已完成：社区分页文案（2026-11-24）

- 按用户要求，CommunityPageBar统一显示“共N条 · 第X/Y页”，总页数按ceil(total/pageSize)计算，翻页逻辑未改。仅一处共享组件文案修改，既有修改保留，无接口或依赖变化。
- test-changed -PlanOnly后执行scripts/test-changed.ps1退出0：工作流31条断言、前端101项、类型检查和生产构建通过，证据runtime/workflow/20261124-162118-456-e2fd1b24/{plan,result}.json及日志。
- 真实Edge复验显示“共2条 · 第1/1页”，两条记录、前后页按钮禁用、无pageerror，runtime/community-pagination-browser.json及community-pagination-after.png。首次临时验收脚本因正则中的斜线未转义而语法失败，改成精确文本匹配后退出0；不是页面错误。
- 本次差异及共享组件消费者已审查，git diff --check通过；未运行后端或全量E2E，未提交推送。

## 已完成：社区测试文章清理（2026-11-24）

- 用户要求保留两条测试数据。已确认144项公开文章全部是浏览器验收夹具，备份原库后在单事务删除142篇及相关档案、版本、收藏/点赞/历史；保留资源78、79。账号、其余草稿和知识资源未改。
- 真实Edge复验HTTP200、total=2、两条列表记录、“第1页，共2项”，翻页按钮禁用，无pageerror。命令/事务边界与备份路径见 [测试文章清理记录](docs/workflow/tasks/2026-11-24-community-fixture-cleanup.md)。无业务代码修改，不提交或推送。

## 活动排查：原站点社区不可用（2026-11-24）

- 已解决：5173 登录后分区和帖子列表 HTTP500，原因是8080仍运行11月19日旧JAR；发布编辑器还因原node_modules缺dompurify/markdown-it无法加载。已重新打包、按锁文件npm ci并通过dev.ps1更新原服务。
- 当前5173/8080/8000正常启动，原库V6→V9；升级前备份在ignored runtime，用户及正文版本指纹一致。后端13项、前端101项及构建通过；真实Edge的列表、筛选、分区详情、发布编辑器和刷新加载通过，无pageerror。未跑全量E2E，未通过浏览器发布合成内容。
- 本任务证据、命令、结果及边界见 [社区运行版本排查](docs/workflow/tasks/2026-11-24-community-runtime-debug.md)。不覆盖既有工作流修改，不提交或推送。

## 活动排查：demo账号登录（2026-11-24）

- 用户确认地址5173。五个账号接口登录及真实Edge表单登录/刷新会话均通过，未改密码、账号或业务代码。
- 当前不能复现用户异常；等待具体错误提示与账号，继续定向定位。证据、命令与边界见 [登录排查记录](docs/workflow/tasks/2026-11-24-demo-login-debug.md)。

## 当前恢复检查点：第三阶段第一批已验收并推送（2026-11-24）

- 当前主分支：`main`，远程已核对 `1cdbceb1395845d2d684090be5674fcbe1c261c4`，中文提交“完成第三阶段第一批社区功能与验收”。本提交与隔离验收分支的已提交 tree 完全一致；不推送 codex 分支。
- 本批范围：两级分区、成员、讨论与评论版本、文章文献及长文额度、社区与管理界面。完整结果与边界见 `docs/11-第三阶段第一批实现与验收.md`；整个第三阶段仍有后续治理与专业审核批次。
- 当前隔离工作区：`C:/Users/86150/.codex/worktrees/stage-three-batch-one/tcm-learning-platform`，本地分支 `codex/stage-three-batch-one`，HEAD `3dcaec79`。服务入口 `http://localhost:5174`，后端8081、AI8001；原项目数据库与服务未改动。
- 验证：隔离工作区 `./scripts/test-backend.ps1` 退出0，169项通过并打包；`runtime/stage-three-accepted-backend.log`。前端101项及构建、AI24项通过；新JAR运行Edge全量15项通过，无跳过或flaky，`runtime/stage-three-accepted-e2e.log/.exit`与`runtime/playwright-results.json`。最终规格及独立质量审查均通过，日志均位于隔离工作区runtime。
- 原main学习注释已保留，重复KnowledgeService.update方法按用户确认仅留完整备份和stash，不合入。备份位于隔离工作区 `runtime/main-user-changes-20261124-36107715`，stash提交 `031b92a972e81494b2c8981395bfa366d8bed10a`；不要整份pop，以免重新引入重复方法。
- 独立工作流任务的27份新增文件及README/findings的入口说明仍保留本地未提交，未纳入上述社区提交。下方记录是该任务的历史快照；其中基线、73表、旧前端55项和重复方法编译阻塞不再代表当前业务代码。模块索引和测试选择策略需在后续工作流任务中按新社区模块校准。
- 下一步：本次恢复和已授权交付已完成。继续业务时进入第三阶段第二批治理闭环，先核对既定设计和数据/API方案；不要将第一批等同整个第三阶段完成，也不要无条件重复全量测试。

## 历史任务：标准化开发工作流（2026-11-24）

- 基线：`9341620d`；本轮不修改业务逻辑，不提交或推送。
- 范围：项目规则、模块索引/分模块设计、六个项目 Skill、环境及增量测试脚本。
- 计划：①核实现有入口；②编写工作流与模块文档；③实现测试选择；④场景验证与审查；⑤记录结果与恢复入口。五项已完成；业务后端回归存在下述既有编译阻塞。
- 完成情况：复用 README / PRODUCT / DESIGN、原设计和 dev.ps1/test-backend.ps1；确认当前迁移 V1–V6。已建立短规则、模块索引、10份模块设计入口、决策记录、6个精简技能、测试选择与证据脚本。完整使用说明和文件清单见 docs/workflow/README.md。
- 开始前已有修改：PersonalCenterService、ReviewService、SecurityConfiguration、TokenService、KnowledgeService、contracts/README.md、docs/05-开发顺序与验收.md；另有两份第三阶段 specs 未跟踪。全部保留，不视作本轮完成内容。
- 本轮文件：新增27份、修改2份，清单见下节；PRODUCT.md / DESIGN.md / 既有业务代码和迁移均未改动。
- 遗留：KnowledgeService 已有未提交代码包含重复 update 方法，阻止当前后端编译；本任务按“不修改业务逻辑”约束保留原样。前端/AI 通过不代表当前全栈可重新构建运行。
- 下一步：新开发任务从本文件和 MODULE_INDEX 对应模块恢复；若后续处理编译问题，先比较 KnowledgeService 第87、125行同签名方法与原设计，再在该业务任务内修复及回归。本次不自动扩展到业务修复。

## 本次验证（2026-11-24）

| 检查/命令 | 结果 | 证据/范围 |
|---|---|---|
| `./scripts/check-env.ps1` | 退出0 | JDK17.0.12、Maven3.9.9、Node22.22.3、npm10.9.8、Python3.11.18、MySQL8.0.42；隔离库53793监听。应用端口已占用，仅提示未重启；TCP不等于数据库鉴权证明 |
| check-env 子进程传入不存在的 JavaHome | 符合预期，退出1 | JDK 检查明确 failed，验证环境失败不会假通过 |
| `./scripts/tests/workflow.Tests.ps1` | 退出0，31条断言通过 | 最后一次 fixture：runtime/workflow-fixture-68e67411f5714c65bd46ee810aa1ee11；包括依赖扩散、公共/契约/未知路径、Java新增测试、中文/空格、暂存与工作区反向修改、删除/重命名、分支基线、错误退出和结果落盘。fake runner 仅为脚本自检，不计入业务测试 |
| `./scripts/test-changed.ps1` | 退出1，正确中止 | runtime/workflow/20261124-151634-417-dcc2946f/{plan,result}.json、backend.log；已有身份/契约修改触发全自动计划，工作流自检通过，Java编译失败，后续步骤未执行 |
| `./scripts/test-module.ps1 -Module all -Layer frontend,ai` | 退出0 | runtime/workflow/20261124-151738-551-c90694db/{plan,result}.json：前端10文件55测试通过，vue-tsc + Vite构建通过，AI24测试通过（1条依赖弃用提示） |
| 文档/索引静态核验 | 通过 | 23份相关Markdown相对链接可解析；6份Skill名称/description/长度检查；索引73个表名均见实际迁移，覆盖全部当前业务表；6份PowerShell脚本语法检查通过 |
| E2E / 服务重启 / Java打包 | 未执行 | 本次不改业务，不启动全量E2E、不重启已有应用；不能把旧运行JAR或历史验收当作当前工作区通过 |

后端确定原因：KnowledgeService.java 第87与125行各定义 `update(long actor,long resource,KnowledgeInput in)`，新增方法与保留的原方法重复；Maven在第125行报同签名重复定义，尚未进入测试阶段。没有原样重试，也没有改动该文件。

审查结论：本次新增文件未引入业务逻辑变更；未知路径保守扩大覆盖、跨共享表扩散、失败即停且留证据；修正了自检函数与原生 git 同名造成递归的问题，并将自检 fake 结果隔离在 fixture 目录，防止被误读为真实回归。最后一次脚本调整只涉及选择和证据目录，已重跑31条自检；未重复运行源码未变化的前端/AI测试。

## 文件清单

- 新增根入口：AGENTS.md、MODULE_INDEX.md、DEVELOPMENT_STATE.md、DECISIONS.md。
- 新增模块文档：docs/modules/{identity,account,source,content,knowledge,platform,community,qa,learning,recommendation}.md（10份，具体路径见工作流指南）。
- 新增指南：docs/workflow/README.md。
- 新增技能：.agents/skills/{project-context,api-contract,targeted-debug,incremental-test,session-resume,code-review}/SKILL.md（6份）。
- 新增脚本：scripts/test-module.ps1、scripts/test-changed.ps1、scripts/check-env.ps1、scripts/Test-Policy.ps1、scripts/Invoke-TestPlan.ps1、scripts/tests/workflow.Tests.ps1。
- 修改：README.md 增加日常入口；findings.md 增加历史快照说明。
- 未改动：所有开始前已有业务/契约/阶段设计修改；PRODUCT.md / DESIGN.md；scripts/dev.ps1 / test-backend.ps1；迁移与依赖锁文件。运行日志、fixture、构建产物在忽略目录，不提交。

## 记录约定

每项任务记录目标/边界、分支与基线、完成/进行中/待处理、修改文件、关键决策引用、命令/环境/退出码/结果/日志、未验证项及下一条动作。凭据只记 runtime 文件路径，不记内容。
测试复用须满足相关源码、传递依赖、迁移、锁文件、配置和环境未变；只检查目标文件时间戳不充分。状态与 Git 冲突时先核对差异，不能据文档覆盖代码。
多人/多任务并行时本文件只保留活动任务索引，各任务用 docs/workflow/tasks/<task-id>.md 独立记录，避免互相覆盖。此目录按需创建。
