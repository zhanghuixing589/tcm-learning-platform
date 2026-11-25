# 全站视觉重构交付与验证

日期：2026-11-24；基线：`1cdbceb1`；在用户指定工作区直接实施，未提交或推送。先完成 [外部调研](reference-analysis.md)，再实施全站视觉升级；[设计规范](../../DESIGN.md)、[素材来源及提示词](assets.md) 已同步。

## 页面与公共结构

| 区域 | 修改与保留 |
|---|---|
| 学习门户 HomeView / HomeContent | 植物主视觉、学习路径、登录后的真实知识选读与社区新声、贡献引导、未开放能力；接口独立失败、旧会话回写保护 |
| 全站 PortalLayout | 叶片品牌、主导航、按既有权限呈现的工作空间、个人入口、手机展开、Esc与点外部关闭；原路由、权限、登录落点不变 |
| 知识中心 KnowledgeListView | 类型索引、搜索/分类/类型筛选、版本记录卡、分页；目录高亮依据已应用的URL条件而非未提交表单 |
| 知识详情 KnowledgeDetailView / KnowledgeRead | 白色正文、行宽与行距、事实/依据分节、阅读锚点；收藏、纠错、版本比较与停用操作保留 |
| 社区 CommunityListView / PostFeed | 208px分区与宽内容列、紧凑筛选、文章/讨论差异、作者头像/日期/分区/评论点赞；手机目录压缩；无封面字段不伪造图片 |
| 个人中心 AccountView | 原名片、收藏/历史/发表/点赞与社交统计结构；更清晰的列表和辅助栏，个人知识空间引导与真实未开放说明 |
| 贡献资格 ApplicationsView | 申请流程、白色表单、状态标签；申请条件、历史、提交参数保持原样 |
| 贡献与审核 WorkbenchView / ReviewsView / JobsView | 工作流程、任务区、统一状态、筛选与操作；来源上传、解析、审核冻结、反馈和版本规则不变 |
| 后台 AdminLayout / AdminUsersView | 侧栏、任务页头、表格、分页、弹窗统一；长用户名/昵称省略并可悬浮查看；分区/额度/政策等已有管理页沿公共样式升级 |
| 其他已有页面 | 登录注册、资料设置、公开主页、来源/知识/讨论/文章编辑、导入、比较、审核详情沿全局及模块样式统一；未重写其业务实现 |

新增公共组件：`app/ui/BotanicalPlate.vue`、`ContentGlyph.vue`、`StatusBadge.vue`。新增首页内容组合 `modules/account/HomeContent.vue`；复用 UserAvatar、WorkspaceState、Element Plus、两个既有分页组件，无新增依赖。

## 样式组织

- `frontend/src/app/tokens.css`：色彩、字体、间距、宽度、圆角、层级及Element主题；body作用域防止懒加载主题回退为蓝色。
- `frontend/src/app/styles.css`：基础排版、控件、可访问焦点、状态、弹层；移除旧门户/后台外壳重复定义。
- `frontend/src/app/portal.css`：门户/后台布局、导航、手机菜单、资格流程及控件组合。
- `frontend/src/modules/account/home.css`：首页独立的编辑式图文编排。
- `frontend/src/modules/account/account.css`：个人名片、内容列表与辅助栏。
- `frontend/src/modules/knowledge/stage.css`：工作台基本控件、知识目录、阅读布局与贡献流程。
- `frontend/src/modules/community/community.css`：分区、筛选、信息流、文章阅读、评论与响应式布局。

## 本次验证

| 检查 | 本次结果与边界 |
|---|---|
| `scripts/test-changed.ps1 -PlanOnly` | 已执行；文档PNG被保守规则归为未知路径而扩大到全栈。审查确认只改前端呈现、只读预览与文档；backend/ai-service/router/shared API与store/依赖锁/迁移均无diff，因此显式选择全前端，不绕过任何本次后端安全改动 |
| `scripts/test-module.ps1 -Module all -Layer frontend` | 退出0，19文件105项测试通过，含新增HomeContent四项：访客不请求、单接口失败独立显示、退出后丢弃旧请求、真实空状态；vue-tsc与Vite构建通过。证据 `runtime/workflow/20261124-170825-289-25c410b1/` |
| 最后纯样式/目录高亮修正后 `npm run build` | 退出0；日志 `runtime/design-final-build.log`，包含vue-tsc与Vite。未因纯样式再重复全部单元测试 |
| Edge真实业务只读检查 | 使用原5173/8080服务与runtime现有学习者、贡献者、审核者、管理员；四尺寸1440/1024/768/390；广泛轮52项通过，审核页桌面问题另有有效补验（见下），无pageerror；`runtime/design-validation/result.json` 与页面截图 |
| 最终定向浏览器复验 | 16项通过，零pageerror、零API 5xx；门户桌面/手机、菜单Esc焦点/点外部关闭、社区手机及我的分区、主按钮颜色、资格页、审核桌面/手机、后台表格/弹窗、技术任务、未提交类型不抢占目录高亮。`runtime/design-validation/final-result.json` |
| 浏览器独立夹具 | 10项通过、零pageerror；知识卡片六类型/长标题、四尺寸、详情桌面/手机、锚点、错误与重试、首页图片失败回退；`fixture-result.json`。仅网络拦截的合成数据，不写数据库，不能当作真实知识发布验收 |
| 差异审查 | `git diff --check` 退出0；新文件与调用者核对；既有README/findings/工作流及社区分页改动保留，未改后端契约或业务数据 |
| 未执行 | 无独立lint/typecheck脚本（typecheck包含在build）；未运行后端/AI测试、全量E2E、真实发布/审批/上传等写入链；本轮不能替代完整业务验收 |

真实浏览器检查还覆盖社区搜索/类型/排序/空结果/详情，知识搜索与空状态，个人中心，贡献者资料上传与知识/讨论/文章编辑入口，以及管理后台各既有管理页。上述页面未发现页面级横向溢出。表格保留内部滚动。浏览器只读检查没有提交或删除业务内容，也没有更改账号、密码、角色或审批结果。

## 调整中发现的问题

- 浏览器验收脚本首次把登录落点写成 `/`，实际既有默认是 `/account`；下拉框标签含选项，精确匹配失败；后续新增类型目录又使模糊标签匹配包含nav。已分别改为既有落点及明确select定位，不修改认证行为。失败记录保留，不能把首次结果计为通过。
- ReviewsView 接入状态组件时，自闭合斜线与右尖括号被拆开，导致开发服务模板编译失败；修正为完整 `/>` 后，生产构建和最终桌面/手机审核页复验通过。原广泛轮的一个失败被明确的最终补验覆盖。
- Element Plus按需CSS加载会重置根主题变量，导致后台按钮/标签回蓝；将Element变量设置在body后，真实懒加载管理页主题色复验通过。
- 首页植物板过大的顶角裁切了图注区上方标签；调整为小圆角，最终截图已检查。手机社区目录原先占满第一屏，改为紧凑换行，保留子分区与局部滚动。
- `dev.ps1 status`不受脚本支持（仅start/stop），命令参数校验失败且未修改服务；改用实际端口和HTTP/页面验证，复用既有服务。

## 保留边界与后续

1. 当前库没有已发布知识条目，真实知识中心验证为空状态；有内容的卡片与详情使用明确的独立夹具补验。未为了截图向库中写入内容或审批现有来源。
2. 个人文档知识库、RAG、学习测验及推荐没有现成完整页面，本次明确“尚未开放”，未借视觉重构扩大业务范围。
3. 内容DTO无真实封面字段，因此使用类型插图；若未来增加封面，需要独立契约、上传和权限设计。首页插画为AI装饰素材，不作鉴别图谱。
4. 参考站访问限制和未验证交互均在参考分析中记录，不将403页面或展示截图当作完整产品使用经历。

预览入口：[本地学习门户](http://127.0.0.1:5173/)。截图在忽略目录 `runtime/design-validation/`；`home-preview.png`为最终桌面首屏，`home-final-mobile.png`和`community-final-mobile.png`为手机效果。原始生成PNG保留在工具目录，项目实际引用本地WebP。
