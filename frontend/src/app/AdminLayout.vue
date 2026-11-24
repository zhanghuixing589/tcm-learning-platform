<script setup lang="ts">
import { useSessionStore } from "../shared/stores/session";
const session = useSessionStore();
</script>
<template>
  <a class="skip-link" href="#main">跳到主要内容</a>
  <div class="admin-shell">
    <aside class="admin-sidebar">
      <RouterLink to="/" class="brand">中医科普 · 管理</RouterLink>
      <nav aria-label="后台导航">
        <RouterLink
          v-if="session.user?.permissions.includes('system:user:manage')"
          to="/admin/applications"
          >资格申请</RouterLink
        ><RouterLink
          v-if="session.user?.permissions.includes('system:user:manage')"
          to="/admin/users"
          >用户管理</RouterLink
        ><RouterLink
          v-if="session.user?.permissions.includes('system:job:manage')"
          to="/admin/jobs"
          >技术任务</RouterLink
        ><RouterLink
          v-if="session.user?.permissions.includes('community:section:manage')"
          to="/admin/community/sections"
          >分区管理</RouterLink
        ><RouterLink
          v-if="session.user?.permissions.includes('community:quota:manage')"
          to="/admin/community/quotas"
          >正文扩容审批</RouterLink
        ><RouterLink
          v-if="session.user?.permissions.includes('community:section:manage')"
          to="/admin/community/policies"
          >社区文本政策</RouterLink
        ><RouterLink
          v-if="
            session.user?.permissions.some((p) =>
              ['knowledge:review', 'community:moderate'].includes(p),
            )
          "
          to="/reviews"
          >内容审核</RouterLink
        ><RouterLink to="/account">返回个人中心</RouterLink>
      </nav>
    </aside>
    <main id="main" class="admin-main" tabindex="-1"><RouterView /></main>
  </div>
</template>
