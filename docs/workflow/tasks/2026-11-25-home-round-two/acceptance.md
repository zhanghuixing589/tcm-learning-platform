# 首页第二轮优化交付与验证（2026-11-25）

已完成，入口 http://127.0.0.1:5173/ 。复用 scripts/dev.ps1 管理的现有5173/8080运行服务，未重启、改写账户、添加数据库内容或提交推送。

## 修改结果
- 保留Logo、导航/工作空间、暖白/药草绿、宋体主标题与植物图鉴，桌面Hero从620px降到492.17px（约20.6%）；下一模块起点从750px提前到622.17px。1440×900和1280×800首屏可见学习入口。
- 首页搜索复用知识中心q参数，去首尾空格、空查询浏览全部；游客经既有登录守卫保留查询并回跳。
- 整块可点击的中药材、方剂、中医概念、社区入口，明确悬停与键盘焦点。用户确认采用“中医概念”，路由使用现有CONCEPT。
- 知识与社区各自加载、失败、重试，保留latestRequest丢弃旧响应；退出/换账户/卸载不接受旧会话回写。
- 知识为空紧凑提示，只有一项时上下排列，多项时按视口及社区内容切换分栏；社区真实摘要、分区、作者和评论/赞/收藏可读且能进入详情/公开主页。
- 知识摘要接口无封面/来源名称，帖子接口无封面；采用无图编辑式条目。来源入口进入已有知识详情的依据部分；未从正文提取图片、伪造来源或增加详情请求触发首页浏览记录。
- 贡献按钮依据登录状态与contribution:submit分别进入登录/资格/工作台，待审与资格状态继续由原资格页处理。压缩共建区，未开放四项能力统一为无按钮预告。
- 辅助文字多数调整为14px；图鉴标注12px，保留系统颜色变量。平板端为图鉴说明预留空间，手机入口双列、内容单列，无页面横向溢出。

## 修改范围与审查
业务文件仅 frontend/src/modules/account/{HomeView.vue,HomeContent.vue,home.css}，测试为 HomeView.test.ts 与 HomeContent.test.ts。同步 MODULE_INDEX.md、docs/modules/account.md、DEVELOPMENT_STATE.md 与本任务记录。保留开始前全站未提交修改；基线副本在runtime/home-round-two/，按副本定向比较，未把第一轮差异算成本轮。

自审未发现需要修复的权限、旧请求污染或路由契约问题。无后端、迁移、依赖、认证组件或全站导航变更；现有account测试规则覆盖新增测试，无需修改Test-Policy。

## 本次验证
| 命令/检查 | 结果及边界 |
|---|---|
| scripts/test-changed.ps1 -PlanOnly | 退出0；整个脏工作区的既有截图未知路径保守扩大为全层，本轮审查确认仅首页前端，定向全前端验证 |
| 新增回归首次运行 | 原实现5项失败，证明缺少搜索/角色路由，以及知识阻塞社区、重试相互影响；实现后通过 |
| scripts/test-module.ps1 -Module all -Layer frontend | 最终退出0，21文件121项测试通过，vue-tsc + Vite构建通过；runtime/workflow/20261125-093504-314-249fdfdb/{plan,result}.json |
| node runtime/home-round-two/browser-check.cjs | 最终退出0，Edge无头真实浏览器32项通过，pageerror为0；结果runtime/home-round-two/browser-result.json |
| 四视口/状态矩阵 | 1440×900、1280×800、768×1024、390×844均验证有数据/空数据/失败布局，无横向溢出；加载/独立重试、单项布局另有验证 |
| 真实业务跳转 | 搜索中文与空查询、登录回跳、三类知识筛选、社区入口/真实详情、工作空间菜单与手机导航、普通学习者资格页与贡献者工作台通过 |
| 数据边界 | 真实库知识仍为空，社区有两篇既有公开内容；有知识/来源详情/空社区/503/延迟使用隔离浏览器响应夹具，未写数据库，不代表真实知识发布链验收 |
| 未执行 | 后端/AI测试、全量E2E、知识贡献审核发布写入；本轮无相关修改，不把旧验收当本轮证据 |

浏览器脚本失败记录：首次把中文编码URL当明文比较，登录未继续导致游客态连锁失败，停止后改为URLSearchParams比较；第二次社区夹具误拦/api/v1/community/posts，实际API为/api/v1/posts，导致3项状态测试失败，修正精确路径后最终32项通过。失败日志/结果仍保留在runtime，未计作通过。文档脚本首次Python编码识别失败，核实文件可用UTF-8完整解码后显式解码执行；未影响业务文件或测试结论。

## 前后截图
截图均位于 runtime/home-round-two/；before/after为相同真实学习者账号与视口。有知识截图均以fixture标识。

| 视口 | 修改前 | 修改后 |
|---|---|---|
| 1440 | [修改前](../../../../runtime/home-round-two/before-1440.png) | [修改后](../../../../runtime/home-round-two/after-1440.png) |
| 1280 | [修改前](../../../../runtime/home-round-two/before-1280.png) | [修改后](../../../../runtime/home-round-two/after-1280.png) |
| 768 | [修改前](../../../../runtime/home-round-two/before-768.png) | [修改后](../../../../runtime/home-round-two/after-768.png) |
| 390 | [修改前](../../../../runtime/home-round-two/before-390.png) | [修改后](../../../../runtime/home-round-two/after-390.png) |

各尺寸fixture-populated-*.png、fixture-empty-*.png、fixture-error-*.png分别记录正常/全空/双区失败。此前真实服务已在运行，本轮复用并验证，不为“启动”重复重启服务。
