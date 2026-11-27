# 来源阅读第二版实施与验收

范围依据：D18及已确认第二版设计。开始前的EPUB、治理规划与全部未提交修改保留；未提交推送，不进入G1或真实RAG执行。最新恢复日期：2026-11-27。

交付状态：本轮第二版实施及所列业务验收已完成，应用运行于5177。测试通过范围、历史失败与能力限制分别记录如下，不把旧全量基线计为最终全量重跑。

## 实现与数据

- 上传文件→书目建议与人工修正→四项授权确认→异步解析。来源详情管理状态、重试及审核；阅读页使用章节/页码及能力驱动模式，旧片段定位保留兼容。
- 人工阅读单元与RAG切分分离；阅读版本、原文块与引用快照不可变。重新解析追加版本，不覆盖历史引用；公开读取使用审核冻结版本。
- 选文按Unicode码点校验精确范围与哈希，保存资料/解析/阅读版本及定位；个人跨书摘录、六类型共享表单、知识草稿与独立修订建议沿用审核发布机制。
- V11/V12/V13均已执行。V13只冻结提交时的授权快照；授权缩减保留独立事件，不伪造历史快照。
- MinIO属于同一`tcm-learning-platform` Compose项目，独立容器和持久卷，私有桶`tcm-learning-sources`，19000/19001端口。6个历史LOCAL原件已迁移，原ID、原文件与授权关系保留；重建容器后全部读回校验通过。迁移前全库备份及指纹在ignored runtime，密钥不写文档。

## 已有验证证据

日志均位于`runtime/source-reader-design/`；通过结论仅适用于对应输入与源码快照。

| 范围 | 结果及边界 | 日志 |
| --- | --- | --- |
| 后端完整基线 | 209执行，206通过、3失败；不标记最终全量通过 | v2-backend-all.log |
| 修复后的共享后端 | 84通过，含V13升级、权限、并发版本、审核冻结、旧异步回写及引用 | v2-backend-final-shared.log |
| 前端完整基线 | 41文件188项通过，类型/Vite构建通过 | v2-frontend-final-all.log、v2-frontend-final-build.log |
| AI真实容器 | 完整72项通过、0跳过；含真实二进制DOC、扫描PDF、媒体、安全预览、资源隔离 | v2-ai-all-real-final.log |
| 工作流规则 | 33项通过，增量策略覆盖新增共享依赖 | v2-workflow-final.log |
| 存储迁移及持久化 | 6个原件迁移，重建后读回核验、0个LOCAL | v2-storage-apply-fixed.log、v2-storage-after-recreate.log |
| 11月27日通知修复 | 独立提案审核通知原来误导向原作者编辑页；新增断言先失败，修复后16项专项通过 | v2-proposal-notification-red.log、v2-proposal-notification-live-store.log |
| 11月27日向导修复 | 许可控件独立可访问名称/描述；3项定向及类型/Vite构建通过 | v2-wizard-resume-one-worker.log、v2-wizard-resume-build.log |
| 最终前端专项 | 4文件13项通过；含新提案跳转后可提交、PDF翻页保留原始模式、拒绝原件交付等回归；类型/Vite构建通过 | v2-pdf-page-mode-green.log、v2-delivery-frontend-build.log |
| 最终真实浏览器 | EPUB及知识整理2条通过；PDF/DOCX/TXT/DOC增强专项单独1条通过，合计3条完成，未伪称同一命令3条全绿 | v2-browser-delivery.log、v2-browser-pdf-final.log |

通知修复复验命令：`./scripts/test-backend.ps1 -TestPattern 'SourceReadingIntegrationTest,KnowledgeProposalIntegrationTest'`，本次以512MiB JVM上限执行。最初Docker未运行导致上传503的16项执行不能算业务失败修复验证；恢复真实MinIO后才得到上述通过结论。

## 浏览器验收与当前环境

先前失败日志保留，不算通过。下拉框改用角色与可访问名称定位；进度首次保存前允许null，轮询等待真实保存。审核夹具原来在领取异步完成前用count检查而跳过覆盖表单，改为等待真实表单/可靠单元可见再逐项勾选，没有绕过审核警告或后端校验。

Edge无头模式反复卡在正常点击的稳定性检查。最小诊断记录按钮位置连续不变、可见、无动画，但连续三次requestAnimationFrame均未回调（wizard-stability-before-occlusion.json）。仅测试进程加入`--disable-features=CalculateNativeWinOcclusion`后，三次动画帧及正常点击均恢复，诊断1条通过(v2-wizard-occlusion-debug.log)；未使用强制点击或改全局浏览器/网络配置。临时诊断测试文件已移除，诊断数据在runtime/source-reading-acceptance。

截图发现新增页面部分原生控件缺少视觉规范，且向导样式放在阅读页懒加载CSS中。补齐仅source-workflow作用域的控件与向导样式，阅读页复用stage表单基础、白色阅读纸面与移动端边距。此变更后须更新浏览器截图与验证，不沿用旧截图宣称最终视觉验收。

最终代码还修正两处实际交互缺陷：新建提案保存后路由加载必须释放旧保存状态，否则提交按钮永久禁用（v2-proposal-busy-red.log先失败）；同一本PDF翻页保留获授权的原始页面模式，切换资料、选择其他排版或搜索精确定位时退出原始模式（v2-pdf-page-mode-red-fixed-fixture.log先失败）。最初PDF单元夹具的异步模块mock错误单独保留在v2-pdf-page-mode-red.log，不把夹具错误当成正确RED。

PDF增强验收真实加载PDF.js文字层，翻至第二页后检查画布像素变化；普通读者仅请求安全页面图，不请求或交付原始PDF。此专项首次增强时存在测试脚本连续goto/reload中断会话恢复，去掉多余reload后原失败方法复验通过；没有改认证或放宽权限。

最终验收覆盖：

- EPUB真实上传、自动元数据建议及人工书目、确认后异步解析、不可变单元/目录、全文搜索、字号与进度刷新恢复、1440/1280/768/390四视口、目录抽屉与焦点恢复、脚本/外部资源隔离、来源审核和原件下载门禁。
- 实际《伤寒论》私有处理得到25个阅读单元，原件下载哈希一致，未公开审批。最新证据actual-book-result.json记录sourceId=329、editionId=67，不保存正文。
- DOCX/TXT连续文字、混合PDF可靠文字/扫描页能力分离、获授权的原始PDF阅读与翻页、公开安全预览、真实二进制DOC转换及原件哈希。DOC为生成的二进制格式夹具，未冒充用户真实教材；最新actual-doc-result.json记录sourceId=338、editionId=76。
- 精确Unicode选文、个人摘录、手机底部面板直接保存知识草稿、六种共享表单、跨资料多引文、知识提交与审核、他人独立提案私有性及审核后发布、原作者身份保持、引文预览和固定版本定位。pageerror检查通过。

截图已更新并人工查看桌面阅读、390手机阅读及手机整理面板，文件为runtime/source-reading-acceptance/synthetic-epub-{1440,1280,768,390}.png、synthetic-mobile-curation.png；仅合成材料，未截取实际书正文。

11月27日Docker恢复后，WSL/Docker转发占用IPv6 8080及8084，通配地址启动失败。未停止转发进程或改全局网络；为本项目设置`SERVER_ADDRESS=127.0.0.1`后通过既有脚本启动成功：

```powershell
$env:SERVER_ADDRESS='127.0.0.1'
$env:JAVA_TOOL_OPTIONS='-Xmx512m'
./scripts/dev.ps1 start -BackendPort 8084 -FrontendPort 5177 -AiPort 8004
```

入口`http://127.0.0.1:5177`；后端8084、内部AI8004。打包日志`v2-resume-package.log`，实际启动成功日志`v2-resume-loopback-start.log`。重启时先使用`dev.ps1 stop`，保留MySQL、MinIO和其他项目。

最终后端/AI均UP，MinIO健康200、匿名桶请求403，见v2-delivery-health.json。Docker中途退出时的三条503失败单独保留v2-browser-flow-final.log；改为隐藏的独立桌面进程启动、恢复同一MinIO数据卷后继续验收，未删除数据或修改全局网络。最终JAR SHA256为73a6b8472a4f35767efa16c2c133156425729adaed22a76a9dc7dd7d154f8e72。

## 适用限制

交付前按code-review单代理自审了本次通知路径、提案异步路由状态、原始PDF/搜索模式切换、控件作用域、既有权限/审核与新旧引用消费者；已发现问题均完成修复和受影响复验，未发现剩余阻断项。增量预览v2-delivery-plan.log与最终git diff --check通过；runtime凭据文件保持Git忽略，未提交推送。

- 复杂书籍的静态预览不承诺任意原CSS、竖排及分页完全一致；降级和局部失败显式警告。预览型单元不能构造可靠文字引用。
- 复杂Word结构走页面预览；扫描PDF不提供OCR文字能力。受限PDF公开阅读使用安全页面预览，不交付原始PDF。
- 实际书只作私有处理验收，不推定版权或自动批准公开。合成夹具只用于安全与流程验证，不冒充真实知识。
- 审核发布、全文阅读、原件下载、引文和RAG授权分别控制；RAG执行属于后续阶段，当前不宣称检索索引已上线。
- 50MiB原件、200MiB解压等是当前配置上限；所列格式、安全和真实书验收不等于接近上限的大文件压力或吞吐基准。
