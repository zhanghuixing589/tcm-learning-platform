# 社区与治理

## 第三轮局部体验（2026-11-25）

保留第二版布局。MarkdownEditor支持ARTICLE/POST创作密度、图标工具栏与自适应正文，继续复用applyMarkdown/CommunityMarkdown。ReferenceEditor只改交互与确认，不改字段或编号；useDiscussionDraft.draftState只在实际localStorage成功后显示已保存。SectionNavigation大量分区搜索、列表讨论收藏、评论提示及日期去重均不增加API。参见[第三轮交付、文件与截图](../workflow/tasks/2026-11-25-community-round-three.md)。

## 当前入口更新（2026-11-25）

下方“空包/第三阶段待实现”是早期快照，已不适用于当前代码。现有CommunityController/CommunityArticleController及第一批社区、文章审核、评论、分区成员已实现；本轮只进行UI/UX第二轮优化，不宣称完成后续治理批次。

创作复用MarkdownEditor→SourcePreview→CommunityMarkdown/markdown.ts，新增markdownEditing选区工具和useDiscussionDraft浏览器讨论草稿；文章复用服务器草稿、版本锁与审核。发现页保留Tab/搜索/排序，requestedPage处理筛选失败后的正确页码重试；CommunityGlyph统一列表/详情图标。CommunityOutline支持唯一标题定位、点击/滚动高亮与动态导航避让。无API/迁移/依赖更改；新社区路径由既有全前端测试规则覆盖。

完整文件、测试、限制及四尺寸截图见 [第二轮交付](../workflow/tasks/2026-11-25-community-round-two.md)。

状态：当前只有个人中心基础文章及通用审核，community 目录为空包；第三阶段完整社区待实现。独立工作树或历史测试不能当作当前 checkout 的完成证据。

## 保留的详细设计

- 业务规则：`docs/superpowers/specs/2026-11-20-stage-three-design.md`。
- 第一批数据/API：`docs/superpowers/specs/2026-11-20-stage-three-batch-one-data-api-design.md`。
- 阶段顺序与验收：`docs/05-开发顺序与验收.md` 第三阶段。

这些设计已分离业务确认与技术建议；实施前只读所负责批次和相关规则，不把全文装入每次上下文。

## 当前实现与后续边界

既有文章由 PersonalCenterController 的 `/me/articles`、content 的审核处理，前端 ArticleEditorView。V1 已建 community_sections、community_post_profiles、community_knowledge_links、community_comments、community_reports、community_moderation_actions；表存在不能证明分区、成员、讨论或举报 API 已实现。

确认方向为基础社区、治理闭环、专业审核增强三批。文章公开与进入 AI 知识库独立；公开修订不覆盖旧版；专业意见复用、授权范围、到期和回退规则按已有 spec 实施，不从本索引增补新规则。

## 实施时的覆盖要求

新增迁移、Controller/DTO、页面和专项测试后同步 MODULE_INDEX、契约和 Test-Policy。验收按既有阶段要求覆盖跨帖子回复拒绝、互动幂等、举报记录/通知、版本隔离及知识准入。

当前 PersonalCenterIntegrationTest 仅覆盖既有文章。`test-module -Module community` 会执行已有基线并明确报告没有社区专项覆盖，不能将其当作第三阶段验收。

## 2026-11-24 视觉入口补充

当前代码已有第三阶段第一批社区页面（旧文档待实现状态不适用于本节）：CommunityListView/SectionNavigation/PostFeed、分区、讨论编辑/阅读、文章版本文献、评论及管理路由均沿既有实现。此次只重组页头、分区与筛选比例和文章/讨论呈现，保留社区分页文案与所有请求/权限条件。DTO 无封面字段，文章采用 ContentGlyph 文稿标识；真实头像仍使用 UserAvatar，不从正文推断封面。样式 community.css 覆盖桌面和手机，后台继续按AdminLayout呈现。
