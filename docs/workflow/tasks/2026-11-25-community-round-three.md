# 社区第三轮精细化交付（2026-11-25）

保留第二版社区与全站视觉基准，聚焦创作空间和局部交互。未进行外部设计调研，未修改后端/API/数据库/依赖/登录/角色/审核规则，未提交或推送。主站仍为5173/8080；真实写入验收复用独立5175/8082及独立数据库，不删除或修改原有验收数据。

## 实际修改

- 编辑器：减少外层表单、编辑器和textarea重复边框，形成一层白色写作画布；标题使用创作标题样式；八项常用工具使用统一SVG图标、原生Tooltip与无障碍名称，支持工具栏左右/Home/End键导航。隐藏未开放的删除线和图片工具，保留Markdown数据格式及原安全渲染。
- 模式：编辑/分屏/预览沿原逻辑，桌面横向组织工具，手机横向滚动图标栏；正文增加占位引导并按内容自适应高度。讨论初始180px，文章初始420px，字数与实际额度保持接口值。工具条sticky，文章顶部保存条通过ResizeObserver测量高度，工具栏按实际高度避让，焦点滚动预留空间。
- 草稿：讨论显示真实本地存储结果（无草稿/待恢复/已保存/不可用），仅在setItem成功后宣称保存，异常保留输入。文章顶部增加“保存草稿”入口，仍调用原save/版本锁/幂等逻辑；状态明确为未保存到服务器、保存中、已保存和失败。冻结/只读、私有身份、额度与审核动作不变，未添加直接发布操作或文章本地草稿。
- 文献：顶部添加入口与数量提示、编号与标题列表、单项展开折叠、字段布局、长列表滚动。删除前明确确认正文引用不会自动删除、其余编号不变。原highWater/newReference规则保留，删除后不重编号也不复用已分配编号，文献字段和结构不变。
- 社区首页：保持三栏，压缩卡片间距；分享者Map本已按用户ID去重，直接保留。分区多于8项时提供本地名称查找，分区导航有高度上限及滚动，真实路由不变。讨论列表补齐实际bookmarkCount和bookmarked状态，与既有详情收藏能力一致，没有增加新的互动请求。
- 详情：相同显示日期不再重复展示更新时间；现有按正文标题决定目录的逻辑直接保留。评论占位、可评论空状态、回复缩进及作者行微调，回复按钮补充aria-controls；保留当前回复层级、长度、冲突与权限规则。

## 本轮文件清单

| 范围 | 文件 |
|---|---|
| 编辑器 | frontend/src/modules/community/MarkdownEditor.vue；新增MarkdownToolIcon.vue；community-experience.css |
| 讨论草稿 | frontend/src/modules/community/DiscussionEditorView.vue、useDiscussionDraft.ts |
| 文章与文献 | frontend/src/modules/account/ArticleEditorView.vue；frontend/src/modules/community/ReferenceEditor.vue |
| 社区局部 | frontend/src/modules/community/SectionNavigation.vue、CommunityPostCard.vue、CommunityReader.vue、CommentThread.vue |
| 回归 | MarkdownEditor.test.ts、useDiscussionDraft.test.ts、CommunityReader.test.ts、communityUi.test.ts；新增ReferenceEditor.test.ts；account/ArticleEditorView.test.ts；frontend/e2e/community-experience.spec.ts |
| 维护 | MODULE_INDEX.md、docs/modules/{community,account}.md、DEVELOPMENT_STATE.md及本记录 |

未修改CommunityCompanion去重逻辑、CommunityOutline目录生成、markdown.ts安全策略、后台契约和Design System。按runtime/community-round-three/community-before及ArticleEditorView.before.vue核对本轮差异，未把开始前修改计为本轮交付。新增社区测试/图标文件由现有全前端选择规则覆盖，Test-Policy无需改动。

## 验证与证据

1. scripts/test-changed.ps1 -PlanOnly退出0（runtime/community-round-three/test-plan.log）。既有脏工作区包含其他任务，本轮审查后执行全前端，未扩大到未改动的后端/AI测试。
2. 初始图标/文献确认回归在旧实现2项失败；实施后通过。针对社区、文章编辑器及账户切换先跑15文件79项通过。
3. scripts/test-module.ps1 -Module all -Layer frontend最终26文件147项通过，vue-tsc --noEmit与Vite构建通过；runtime/workflow/20261125-105746-380-5596d62e/{plan,result}.json。最后仅调整工具按钮边框与焦点滚动留白，再次生产构建退出0（runtime/community-round-three/build-final.log），未无条件重复业务套件。
4. 独立环境既有community-experience.spec.ts扩充并通过（1条E2E、7个业务步骤，e2e-with-references.log）：讨论本地恢复/发布、图标选区/分屏/实时预览、点赞/收藏、评论及回复展开收起、文章顶部服务器草稿保存/刷新、文献添加删除与正文[1]保留、提交审核冻结、另一账号审核后引用与真实参考文献呈现、搜索筛选/成员关系。
5. browser-check.cjs最终27项通过，pageerror=0（browser-final.log、browser-result.json）：5种页面×4尺寸、无重复边框/图标名称/无效工具隐藏/占位文字、本地保存与恢复、工具栏键盘/选区、长篇创作工具栏避让、服务器保存中/失败/输入保留、引用预览；另以4视口浏览器响应夹具检查长文章、列表、表格、参考引用与图片/HTML安全处理。
6. 视口1440×900、1280×800、768×1024、390×844；长文夹具部分使用900高。未发现页面横向溢出。真实数据来自独立库，长文响应、未提交输入和503保存失败仅为浏览器验证，不是生产数据或新增假接口。

项目没有独立lint/typecheck脚本，类型检查包含在build中。首轮E2E扩充脚本因Python文件编码识别失败未应用，保留该次旧流程通过日志e2e.log；核实UTF-8并显式解码执行脚本后，以e2e-with-references.log记录包含新文献/顶部保存/回复折叠的通过结果，不将旧用例冒充新增功能验收。

## 截图

目录runtime/community-round-three/。创作页populated截图为浏览器内未提交的验收输入；详情截图来自独立环境真实发布与审核内容。全页截图先回到顶部，长文sticky另留视口截图。

| 页面 | 1440 | 1280 | 768 | 390 |
|---|---|---|---|---|
| 发布讨论 | [截图](../../../runtime/community-round-three/discussion-editor-1440.png) | [截图](../../../runtime/community-round-three/discussion-editor-1280.png) | [截图](../../../runtime/community-round-three/discussion-editor-768.png) | [截图](../../../runtime/community-round-three/discussion-editor-390.png) |
| 文章编辑 | [带文献输入](../../../runtime/community-round-three/article-editor-populated-1440.png) | [截图](../../../runtime/community-round-three/article-editor-1280.png) | [截图](../../../runtime/community-round-three/article-editor-768.png) | [带文献输入](../../../runtime/community-round-three/article-editor-populated-390.png) |
| 社区首页 | [截图](../../../runtime/community-round-three/community-1440.png) | [截图](../../../runtime/community-round-three/community-1280.png) | [截图](../../../runtime/community-round-three/community-768.png) | [截图](../../../runtime/community-round-three/community-390.png) |
| 文章详情 | [截图](../../../runtime/community-round-three/article-detail-1440.png) | [截图](../../../runtime/community-round-three/article-detail-1280.png) | [截图](../../../runtime/community-round-three/article-detail-768.png) | [截图](../../../runtime/community-round-three/article-detail-390.png) |
| 讨论详情 | [截图](../../../runtime/community-round-three/discussion-detail-1440.png) | [截图](../../../runtime/community-round-three/discussion-detail-1280.png) | [截图](../../../runtime/community-round-three/discussion-detail-768.png) | [截图](../../../runtime/community-round-three/discussion-detail-390.png) |

## 边界

本轮无新增业务能力。图片仍遵循现有策略，仅呈现替代文字，不渲染图片或不安全HTML；没有启用上传或删除线。文章保存仍由用户操作触发，明确是服务器草稿；讨论本地草稿不跨浏览器同步。未运行后端/AI全量或完整阶段三E2E，未修改/清理任何原有测试数据。
