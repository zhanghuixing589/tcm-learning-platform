# 当前阶段

Vue3 / TypeScript / Vite 应用，采用 Element Plus 按需组件与 Pinia。已实现门户/后台布局、登录注册、个人资料、贡献资格、资格审批与用户管理页面。

`npm ci` 安装锁定依赖，`npm test` 运行单元测试，`npm run build` 类型检查与构建，`npm run dev` 本地运行。`npm run test:e2e` 使用已运行的三端，管理员场景先从项目根运行 `scripts/prepare-e2e.ps1`。

访问令牌仅存在内存中；刷新令牌为 HttpOnly Cookie。同页刷新合并，跨页通过Web Locks串行；同会话旧401复用新令牌，跨账号旧响应和重试拒绝。登录注销通过BroadcastChannel使其他页失效。Vite 将 `/api` 代理到 Java，默认8080，可通过 BACKEND_URL 调整。
