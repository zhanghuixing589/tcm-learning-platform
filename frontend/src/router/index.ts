import { createRouter, createWebHistory } from "vue-router";
import { useSessionStore } from "../shared/stores/session";
import { canEnter } from "./access";
import PortalLayout from "../app/PortalLayout.vue";
import AdminLayout from "../app/AdminLayout.vue";

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: "/",
      component: PortalLayout,
      children: [
        {
          path: "",
          component: () => import("../modules/account/HomeView.vue"),
          meta: { title: "学习门户" },
        },
        {
          path: "login",
          component: () => import("../modules/account/AuthView.vue"),
          props: { register: false },
          meta: { title: "登录" },
        },
        {
          path: "register",
          component: () => import("../modules/account/AuthView.vue"),
          props: { register: true },
          meta: { title: "注册" },
        },
        {
          path: "account",
          component: () => import("../modules/account/AccountView.vue"),
          meta: { requiresAuth: true, title: "个人中心" },
        },
        {
          path: "account/settings",
          component: () => import("../modules/account/AccountSettingsView.vue"),
          meta: { requiresAuth: true, title: "编辑个人资料" },
        },
        {
          path: "account/posts/:articleId",
          name: "own-post",
          component: () => import("../modules/account/ArticleEditorView.vue"),
          meta: { requiresAuth: true, title: "帖子详情" },
        },
        {
          path: "account/articles/:articleId",
          component: () => import("../modules/account/ArticleEditorView.vue"),
          meta: { requiresAuth: true, title: "发表内容" },
        },
        {
          path: "users/:userId",
          component: () => import("../modules/account/PublicProfileView.vue"),
          meta: { requiresAuth: true, title: "用户主页" },
        },
        {
          path: "resources/:resourceId",
          component: () => import("../modules/account/ResourceView.vue"),
          meta: { requiresAuth: true, title: "内容详情" },
        },
        {
          path: "applications",
          component: () => import("../modules/account/ApplicationsView.vue"),
          meta: { requiresAuth: true, title: "贡献资格" },
        },
        {
          path: "knowledge",
          component: () => import("../modules/knowledge/KnowledgeListView.vue"),
          meta: { requiresAuth: true, title: "知识中心" },
        },
        {
          path: "knowledge/:id",
          component: () =>
            import("../modules/knowledge/KnowledgeDetailView.vue"),
          meta: { requiresAuth: true, title: "知识详情" },
        },
        {
          path: "formulas/compare",
          component: () =>
            import("../modules/knowledge/FormulaCompareView.vue"),
          meta: { requiresAuth: true, title: "方剂比较" },
        },
        {
          path: "sources/:id",
          component: () => import("../modules/knowledge/SourceView.vue"),
          meta: { requiresAuth: true, title: "来源追溯" },
        },
        {
          path: "contribute",
          component: () => import("../modules/knowledge/WorkbenchView.vue"),
          meta: { requiresAuth: true, title: "贡献工作台" },
        },
        {
          path: "contribute/sources/:id",
          component: () => import("../modules/knowledge/SourceView.vue"),
          meta: {
            requiresAuth: true,
            permission: "contribution:submit",
            title: "来源资料",
          },
        },
        {
          path: "contribute/knowledge/:id",
          component: () =>
            import("../modules/knowledge/KnowledgeEditorView.vue"),
          meta: {
            requiresAuth: true,
            permission: "contribution:submit",
            title: "知识稿件",
          },
        },
        {
          path: "contribute/import",
          component: () => import("../modules/knowledge/ImportView.vue"),
          meta: {
            requiresAuth: true,
            permission: "contribution:submit",
            title: "批量导入草稿",
          },
        },
        {
          path: "reviews",
          component: () => import("../modules/knowledge/ReviewsView.vue"),
          meta: {
            requiresAuth: true,
            anyPermissions: ["knowledge:review", "community:moderate"],
            title: "内容审核",
          },
        },
        {
          path: "reviews/:id",
          component: () => import("../modules/knowledge/ReviewView.vue"),
          meta: {
            requiresAuth: true,
            anyPermissions: ["knowledge:review", "community:moderate"],
            title: "审核详情",
          },
        },
        {
          path: "forbidden",
          component: () => import("../modules/account/StatusView.vue"),
          props: {
            title: "暂时无法访问",
            message: "当前账号没有此页面所需的管理权限。",
          },
          meta: { title: "无访问权限" },
        },
        {
          path: ":pathMatch(.*)*",
          component: () => import("../modules/account/StatusView.vue"),
          props: {
            title: "没有找到这个页面",
            message: "请检查地址，或回到学习门户继续。",
          },
          meta: { title: "页面不存在" },
        },
      ],
    },
    {
      path: "/admin",
      component: AdminLayout,
      meta: { requiresAuth: true },
      children: [
        { path: "", redirect: "/admin/applications" },
        {
          path: "applications",
          component: () => import("../modules/admin/AdminApplicationsView.vue"),
          meta: { permission: "system:user:manage", title: "资格申请管理" },
        },
        {
          path: "users",
          component: () => import("../modules/admin/AdminUsersView.vue"),
          meta: { permission: "system:user:manage", title: "用户管理" },
        },
        {
          path: "jobs",
          component: () => import("../modules/knowledge/JobsView.vue"),
          meta: { permission: "system:job:manage", title: "技术任务" },
        },
      ],
    },
  ],
});
router.beforeEach(async (to) => {
  const session = useSessionStore();
  await session.initialize();
  if (!canEnter(to.meta, session.user))
    return session.user
      ? "/forbidden"
      : { path: "/login", query: { redirect: to.fullPath } };
  return true;
});
router.afterEach((to) => {
  document.title = `${to.meta.title || "学习门户"} · 中医科普学习平台`;
});
