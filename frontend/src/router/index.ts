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
          component: () => import("../modules/community/DiscussionEditorView.vue"),
          meta: { requiresAuth: true, title: "我的讨论" },
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
          path: "community",
          component: () => import("../modules/community/CommunityListView.vue"),
          meta: { requiresAuth: true, title: "科普社区" },
        },
        {
          path: "community/sections/:sectionId",
          component: () => import("../modules/community/CommunitySectionView.vue"),
          meta: { requiresAuth: true, title: "社区分区" },
        },
        {
          path: "community/posts/new",
          component: () => import("../modules/community/DiscussionEditorView.vue"),
          meta: { requiresAuth: true, permission: "community:post", title: "发布讨论" },
        },
        {
          path: "community/posts/:resourceId",
          component: () => import("../modules/community/CommunityDetailView.vue"),
          meta: { requiresAuth: true, title: "社区内容" },
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
        {path:"knowledge/categories",component:()=>import("../modules/knowledge/KnowledgeTaxonomyAdminView.vue"),meta:{permission:"knowledge:taxonomy:manage",title:"知识目录与标签"}},
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
        {
          path: "community/sections",
          component: () => import("../modules/community/CommunitySectionsAdminView.vue"),
          meta: { permission: "community:section:manage", title: "分区管理" },
        },
        {
          path: "community/quotas",
          component: () => import("../modules/community/CommunityQuotasAdminView.vue"),
          meta: { permission: "community:quota:manage", title: "正文扩容审批" },
        },
        {
          path: "community/policies",
          component: () => import("../modules/community/CommunityPoliciesAdminView.vue"),
          meta: { permission: "community:section:manage", title: "社区文本政策" },
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
  if (to.path === "/admin" || to.path === "/admin/") {
    const permissions = session.user?.permissions ?? [];
    if (permissions.includes("system:user:manage")) return "/admin/applications";
    if (permissions.includes("community:section:manage")) return "/admin/community/sections";
    if (permissions.includes("community:quota:manage")) return "/admin/community/quotas";
    if (permissions.includes("knowledge:taxonomy:manage")) return "/admin/knowledge/categories";
    if (permissions.includes("system:job:manage")) return "/admin/jobs";
    return "/forbidden";
  }
  return true;
});
router.afterEach((to) => {
  document.title = `${to.meta.title || "学习门户"} · 中医科普学习平台`;
});
