# 知识中心及方剂比较专项交付（2026-11-25）

## 范围与实现

保留已确认暖白/药草绿、宋体标题、Logo及导航。只修改知识目录、知识详情、方剂比较和必要的知识公共组件；首页、社区、后端、数据库、依赖、路由和审核权限规则未修改。工作区开始前已有的全站修改保留，未提交或推送。

| 页面/组件 | 本次结果 |
|---|---|
| KnowledgeListView.vue | 搜索置顶；全部+六类型单套导航，真实分类、清除、实际数量、最近更新排序说明、分页；Enter搜索、切换重置分页、旧响应隔离 |
| KnowledgeCatalogCard.vue | 药材无图图鉴：别名/性味/来源；方剂结构卡：组成/出处/真实功效；概念百科式定义/关联；功效/症状/经络为轻量条目。共用组件按类型采用不同结构与布局 |
| KnowledgeDetailView.vue / KnowledgeRead.vue | 规范名/别名、真实分类/版本/日期、结构属性、原文、来源引文及定位、真实关联；动态章节导航及异步锚点；保留收藏、版本对照、纠错和受权限控制的停用 |
| FormulaCompareView.vue | 搜索分页、已选反馈、移除/清空/替换、2–3项限制；真实横向对照表，药材ID共同/差异标记，保留原始角色/用量/依据；缺项“暂无资料”，手机横向滚动且固定维度列 |
| formulaShelf.ts / FormulaSelectionBar.vue | 唯一共享内存选择，跨页面和分页保留，切换账号清空，刷新清空有明确提示；比较时服务端重新验证当前可见正式方剂 |
| library.ts / library.css | 组成匹配、中文角色/关系标签及展示帮助函数；局部阅读/目录/比较样式，复用全站tokens，无新设计系统 |
| api.ts | 仅补齐现有搜索响应已返回的可选属性类型 KnowledgeCatalogEntry，不修改请求、DTO或服务端接口 |

新增/增补测试：KnowledgeCatalogCard.test.ts、KnowledgeListView.test.ts、FormulaCompareView.test.ts、formulaShelf.test.ts、KnowledgeDetailView.test.ts；libraryTestFixtures.ts仅为组件测试数据，生产视图不导入。新增 frontend/e2e/knowledge-library.spec.ts。同步 MODULE_INDEX.md、docs/modules/knowledge.md、DEVELOPMENT_STATE.md。

## 模型及边界查证

- 搜索真实支持规范名、别名、标题与 type/categoryId/page/pageSize；排序固定 updated_at DESC,id DESC。分类接口只含 id/name/code，是后台共享平面分类，没有类型关联/层级，不自行改写数据库分类。
- 搜索实际返回经过公开可见性处理的详情结构，目录直接使用，不额外请求每条详情，避免增加浏览历史。herb仅nature/flavor/originNote/precautions；formula仅originText/functionSummary/indicationSummary/precautions；组成和关系使用真实资源ID和版本ID。
- 正式知识必须通过审核且来源有效；来源及文件继续受原服务端权限保护。页面不输出reviewNote，不新增下载或绕过审核入口。不可见知识不向读者确认其存在。
- 知识正文没有sourceFormat，继续使用Vue文本插值和原纯文本格式，不套用社区Markdown规则或渲染不可信HTML。版本对照仍按原组件懒加载与权限过滤。
- 无药材图片字段，无专用归经属性，无分类层级/类型映射，无可选排序或图谱推荐接口。后续如需这些能力，先设计正式字段与可见性契约；本次不新增虚假图片、医学内容、关联或筛选操作。

## 本次测试证据

证据目录：runtime/knowledge-library/。已先执行 scripts/test-changed.ps1 -PlanOnly（test-plan.log）。工作区含前序任务的大量改动，计划保守扩大；本次无服务端/依赖/迁移变动，实际选择全前端并补真实权限/发布链浏览器验收，未运行后端/AI全量或全阶段三E2E。

- 全前端：npm --prefix frontend test -- --maxWorkers=2，30文件159项通过，tests-final.log。
- 最后补充异步来源锚点后：KnowledgeDetailView + KnowledgeCatalogCard，2文件8项通过，anchor-tests.log；最终vue-tsc与Vite结果见build-delivery.log。
- 首次默认并行全前端出现两个社区文件超时及超时后mock干扰（3失败），保留runtime/workflow/20261125-112814-479-27acd016；降低并发后原两文件13项通过（timeout-isolation.log），随后全前端159项通过。未为此修改社区代码、测试或全局超时。
- 隔离真实E2E：e2e-third.log，1条/4步骤通过。经普通注册、角色赋权、来源提交/独立审核、知识提交/独立审核，产生13条正式验收记录，包含6类型、2药材、7方剂。无直接数据库写入、不清理原数据、不写入主站正式库；验收文案明确非医学资料。早期两次脚本失败分别为测试密码超过12字符和来源创建状态码误写201，均已按真实校验/Controller修正，日志保留，未计为通过。
- E2E覆盖：六类型/真实分类/分页，规范名/别名/标题Enter搜索，六类详情，来源及关联跳转，收藏刷新保持，目录→详情→比较共享选择、跨页选中、上限禁用/移除替换/清空、2方与3方对照、原用量及缺失字段；普通读者无法读取未发布知识/候选版本/受限资料和文件。real-result.json仅含记录ID和非敏感结果。
- Edge真实页面补验：browser-results.json共42项，通过；1440、1280、768、390，主站真实空知识/空方剂、延迟加载、503失败与恢复、分类失败仍保留目录、三类详情、比较滚动固定维度；页面无横向溢出，pageerror=0。延迟/503通过浏览器网络拦截，仅模拟状态，不写库。
- final-details.json：目录直接进入来源hash后，标题顶114.39px、导航底90px，无遮挡；键盘Tab搜索按钮聚焦轮廓3px；辅助文字与背景/纸白/浅底对比度5.63/6.03/5.39:1；pageerror=0。
- 项目没有lint脚本或独立typecheck脚本；build内含vue-tsc --noEmit。最终本次文件git diff --check无空白错误。自审确认无本次API/权限/数据契约变更，选择/搜索/比较旧回写及跨账号清空有回归覆盖。

## 截图

截图均在runtime/knowledge-library/，每组提供1440、1280、768、390四视口。有数据截图来自隔离环境经过正常审核的验收记录；主站仍为空，不以测试知识充填主站。

- 有数据目录：[药材](../../../runtime/knowledge-library/catalog-herb-1440.png)、[方剂](../../../runtime/knowledge-library/catalog-formula-1440.png)、[概念](../../../runtime/knowledge-library/catalog-concept-1440.png)。
- 详情：[药材](../../../runtime/knowledge-library/detail-herb-1440.png)、[方剂](../../../runtime/knowledge-library/detail-formula-1440.png)、[移动端](../../../runtime/knowledge-library/detail-herb-390.png)。
- 对照：[桌面结果](../../../runtime/knowledge-library/formula-comparison-1440.png)、[手机横向表格](../../../runtime/knowledge-library/formula-comparison-390.png)。
- 数据状态：[主站真实空目录](../../../runtime/knowledge-library/catalog-real-empty-1440.png)、[空方剂](../../../runtime/knowledge-library/formula-real-empty-1440.png)、[搜索无匹配](../../../runtime/knowledge-library/catalog-no-match-1440.png)、[失败可重试](../../../runtime/knowledge-library/catalog-request-failed-1440.png)、[不可见知识](../../../runtime/knowledge-library/knowledge-not-visible-1440.png)。

复核服务：原主站http://127.0.0.1:5173，隔离验收http://127.0.0.1:5175。浏览器权限断言以普通学习者执行；补充排版截图部分使用隔离管理员只读访问已发布验收记录。原账户和数据未清理。
