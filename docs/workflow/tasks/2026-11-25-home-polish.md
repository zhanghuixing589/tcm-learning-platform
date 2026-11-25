# 首页第二版细节优化（2026-11-25）

用户确认第二版首页为全站视觉基准，已写入 DESIGN.md。只调整必要细节，保留布局、配色、字体、Logo、植物图鉴和主要文案，无外部设计调研、依赖或后端接口变更。

## 本次修改与跳过项

- HomeView.vue / home.css：搜索保留药草绿实心主按钮，输入框使用现有白色/绿色变量增强识别，探索知识中心改为描边次级入口，社区保留文字入口；移动端首屏辅助文字13→14px。
- HomeContent.vue：复用 community/reading.ts 的 communityExcerpt，正确处理 Markdown 和历史纯文本，保持已有两行截断；作者复用 UserAvatar，首页补齐头像局部样式以避免直接刷新依赖个人中心样式；互动沿社区显示“条评论/个赞/人收藏”，评论链接到真实详情，讨论不显示文章收藏数据。
- 已满足而跳过：四个学习入口整区链接、hover/focus、真实路由；知识选读紧凑空状态与真实卡片分支；未开放能力的紧凑无按钮预告。不重写这些逻辑。
- 测试仅新增两项摘要格式/真实作者与互动链接回归，已有断言随社区互动文案校准。

## 本次验证

- scripts/test-changed.ps1 -PlanOnly 退出0，预览保存在 runtime/home-detail-plan-preview.log。工作区包含第一轮等既有大量修改，本轮以 runtime/home-polish/*before* 副本限定审查，没有将已有差异计入交付。
- 新回归在旧实现2失败，修改后 npm --prefix frontend test -- HomeContent.test.ts HomeView.test.ts src/modules/community/communityUi.test.ts 退出0，3文件19项通过；日志 runtime/home-polish/targeted-tests.log。
- npm --prefix frontend run build：vue-tsc 与 Vite 通过，最终日志 runtime/home-polish/final-build.log。
- node runtime/home-polish/browser-check.cjs：Edge18项通过，pageerror=0。1440×900、1280×800、768×1024、390×844均检查实际页面与有知识夹具、两行摘要、头像、辅助文字与横向溢出；另验整区点击真实路由、hover/键盘焦点、搜索、作者/评论详情、Markdown/历史文本、知识失败和独立重试。
- 辅助文字 #59665C 对现有四种背景的对比度5.12–5.63:1。截图与机器结果在 runtime/home-polish/{home-*,populated-*,contrast.json,browser-result.json}。
- 实际知识库仍为空，有知识及格式边界通过独立浏览器响应夹具验证，未写数据库；未执行真实知识发布流程、后端/AI或全量E2E。本轮没有相关变更，不复用历史通过数字。

## 差异审查与维护

只修改首页三文件、HomeContent.test.ts，以及 DESIGN.md/模块说明/本记录/开发状态。HomeContent新增对communityExcerpt与UserAvatar的复用关系：account测试自动覆盖首页用例，reading.ts位于现有全前端测试选择范围，相关社区工具用例本次已补跑，Test-Policy无需改动。

保留会话/权限判断、latestRequest旧响应丢弃、加载与重试逻辑。未发现本轮可操作的权限或状态污染问题。复用原5173/8080服务，未提交推送。下一步仅按用户具体视觉反馈定向调整，不再次整体重构首页。
