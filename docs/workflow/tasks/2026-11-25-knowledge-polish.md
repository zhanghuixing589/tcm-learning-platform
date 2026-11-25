# 知识中心第二轮精细化交付（2026-11-25）

## 范围

沿已认可的知识目录、详情、比较工作台布局局部优化。保留全站tokens、认证、审核、正式版本、收藏、纠错、分类数据、搜索及分页逻辑；未修改首页、社区、后端接口、数据库或依赖，未清理测试数据，未commit/push。用户追加 session-resume 后核对 main / 1cdbceb1 与工作区，补充进行中状态后继续，未按旧状态回退代码。

## 修改文件与功能

| 文件（frontend/src/ 下） | 改动 |
|---|---|
| modules/knowledge/KnowledgeCatalogCard.vue | 白底与浅绿核心属性、同排对齐；长别名展开、核心字段简化、完整名称换行；“类型/目录”分开标注。主链接覆盖卡片主体，独立按钮/来源/关联/别名展开避让，不套嵌链接 |
| modules/knowledge/KnowledgeListView.vue、KnowledgeTypeIcon.vue（新增） | 保留原导航/说明，加入轻量线性图标及选中指示；原搜索、共享分类、清除、排序和分页不变 |
| modules/knowledge/herbImages.ts（新增）、assets/herbs/{huangqi,chenpi}.png（新增） | 使用用户允许引用的原图；仅HERB规范名完全匹配“黄芪”“陈皮”，不匹配别名/相似名/其他类型；未知条目不显示图片或Logo占位 |
| modules/knowledge/KnowledgeEvidence.vue（新增） | 可复用证据组件：依据编号、实际知识版本、资料名、原文定位、明确标注的原文摘录、原来源详情路由；不新增资料请求、HTML解释或RAG业务 |
| modules/knowledge/KnowledgeRead.vue | 药性/药味紧凑属性，长属性描述列表，真实注意事项独立呈现；复用证据组件，关联名称/关系/描述/依据定位；仍按原纯文本内容展示 |
| modules/knowledge/KnowledgeDetailView.vue | 概览分层、收藏选中状态、参考图并入概览；目录当前章节及锚点避让；版本历史/对照、纠错、受权限保护的停用统一到质量维护，业务条件不变 |
| modules/knowledge/FormulaCompareView.vue | 选择/结果状态切换；成功自动定位并聚焦结果，候选收起；调整恢复原搜索分页/已选；对象变化清除旧结果；失败保留选择并恢复候选，加载中防重复提交 |
| modules/knowledge/library.css | 局部卡片、属性、目录、来源、维护区及比较表样式；表格内部双向滚动，固定方剂表头与左侧维度列；同ID三类文字+配色图例及差异组成筛选 |
| modules/knowledge/KnowledgeEvidence.test.ts（新增）、FormulaCompareView.test.ts | 文本安全/证据路由/版本/图片严格匹配；三种组成、原数组不变、结果切换/搜索保留、旧响应、重复请求、失败恢复 |

另调整 frontend/e2e/knowledge-library.spec.ts：组成数量断言限定到真实结果表，避免把新图例计入；支持 KNOWLEDGE_LIBRARY_OUTPUT 独立存放本轮证据。同步 MODULE_INDEX.md、docs/modules/knowledge.md、DEVELOPMENT_STATE.md。

## 已保留与暂不支持

- 分类仍为后台共享平面分类。截图中测试药材归属“中医概念”目录是隔离测试记录的真实分类，本轮只标明语义，不改数据，不推定后端缺陷。
- 现有知识API没有图片字段/上传或版本审核附件关联。两张照片为用户提供的本地参考资源，不宣称为某版本审核采集照片；未匹配知识不展示，主站未发布知识时仍真实为空。有图验收仅在浏览器响应夹具中匹配规范名，未为图片演示写库。
- 来源组件只使用Evidence中已有字段。作者/出版社等资料信息仍由来源详情展示，没有为每条证据额外请求受限资料。没有增加文件暴露或下载接口。
- “仅查看差异组成”只隐藏在所有比较方剂中均出现的药材ID；原角色、用量、依据保留。不根据名称合并，不比较剂量差异、医学优劣或推断疗效。
- 原账号隔离内存store、刷新清空、2～3选择规则均保留。Markdown、图片上传、类型专属分类、图谱及RAG并未新建。

## 验证与发现的问题

证据：runtime/knowledge-polish/。修改前先运行 scripts/test-changed.ps1 -PlanOnly，test-plan.log。前序工作区改动导致计划保守扩大，本轮仅前端/资源变化，无服务端与迁移，执行全前端并补隔离真实流程；未运行后端/AI全量或全阶段三E2E。项目无lint或独立typecheck脚本，build含vue-tsc --noEmit。

- 全前端：npm --prefix frontend test -- --maxWorkers=2，31文件163项通过（tests-final.log）。
- 最后增加“重复比较失败恢复候选”的修复后，定向 FormulaCompareView / KnowledgeEvidence / KnowledgeDetailView，共3文件9项；结果在tests-delivery.log。最终vue-tsc/Vite在build-delivery.log。
- 隔离真实E2E：knowledge-library.spec.ts，1条/4步骤通过（e2e.log）。仍经普通来源/知识审核，新增13条非医学验收记录；覆盖六类型、三维搜索、分类分页、来源及关系、收藏、普通用户可见性、跨页选择、2方/3方比较。未改写或删除既有记录。
- Edge补验28项通过（browser-results.json）：四视口1440/1280/768/390；真实版本历史/对照、纠错503保留输入并正常重试、自动结果定位/键盘聚焦、调整恢复搜索分页、三类ID组成、仅差异、双向sticky、图片/长别名/长来源及主站真实空态。pageerror=0。
- Edge边界32项通过（edge-results.json）：最终无图目录/真实详情及有图响应夹具截图；长名称、50段正文、12条来源不截正文/不溢出，目录当前章节；目录503/加载/恢复、比较失败保留选择并成功重试；四视口无页面横向溢出，pageerror=0。
- 自动定位曾实测结果顶66.55px，被90px导航遮住；复现发现比较loading提示在scrollIntoView后才移除，导致约43px上移。现改为先结束loading、等待DOM更新再定位，实测结果顶109.55px，二次定位无偏移（debug-result.cjs/log输出，comparison-focused-1440.png）。不是盲目提高z-index或加固定空白。
- 图片补验脚本最初在首次页面加载尚未稳定时马上reload，整套场景偶发未进入目录；单独图片场景加载正常。改为先安装拦截再导航、等待图片完成后，整套28项通过；没有为脚本失败改写图片或数据库。
- 测试早期失败：新图例使旧全局badge计数多1，断言改为结果表内数量；异步按钮文字切换导致测试重复查找不存在按钮，改为保存按钮引用并模拟挂起请求；焦点/可见性测试改为挂载document.body；测试input.value需HTMLInputElement类型。早期日志保留，不计为通过。
- 本次相关文件 git diff --check 无空白错误。自审覆盖共享KnowledgeRead的审核/版本消费者、图片严格匹配、证据文本转义、原权限判断、异步结果失效和选择隔离；无新增服务端契约。

## 修改后截图

每组有1440/1280/768/390。有数据目录和真实详情来自正常审核发布的隔离非医学验收记录。带 image-fixture 名称的图片场景只在浏览器修改响应展示指定规范名及长文本，明确不是正式医学数据，也不填充主站。

- [无图目录](../../../runtime/knowledge-polish/catalog-herb-1440.png)、[方剂目录](../../../runtime/knowledge-polish/catalog-formula-1440.png)、[概念目录](../../../runtime/knowledge-polish/catalog-concept-1440.png)。
- [有图及长来源夹具](../../../runtime/knowledge-polish/catalog-image-fixture-1440.png)、[有图手机夹具](../../../runtime/knowledge-polish/catalog-image-fixture-390.png)。
- [真实知识详情](../../../runtime/knowledge-polish/detail-herb-1440.png)、[详情手机](../../../runtime/knowledge-polish/detail-herb-390.png)、[有图详情夹具](../../../runtime/knowledge-polish/detail-image-fixture-1440.png)。
- [比较结果视图](../../../runtime/knowledge-polish/comparison-results-1440.png)、[手机对照](../../../runtime/knowledge-polish/comparison-results-390.png)、[比较后自动定位视口](../../../runtime/knowledge-polish/comparison-focused-1440.png)。表格内部可滚动，完整长图只显示初始可见表格窗口，来源维度通过表格向下滚动可到达。
- [主站真实空目录](../../../runtime/knowledge-polish/catalog-real-empty-1440.png)、[空方剂](../../../runtime/knowledge-polish/comparison-real-empty-1440.png)、[失败重试](../../../runtime/knowledge-polish/catalog-failed-1440.png)。

本地复核：http://127.0.0.1:5173/knowledge；隔离验收：http://127.0.0.1:5175/knowledge。保留现有服务和账户，不输出凭据。
