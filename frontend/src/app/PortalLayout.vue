<script setup lang="ts">
import { useSessionStore } from "../shared/stores/session";
const session = useSessionStore();
</script>
<template>
  <a class="skip-link" href="#main">跳到主要内容</a>
  <header class="site-header">
    <RouterLink to="/" class="brand"
      ><span class="brand-mark" aria-hidden="true">本</span
      ><span
        >中医科普<span class="brand-subtitle">学习平台</span></span
      ></RouterLink
    >
    <nav aria-label="主要导航">
      <RouterLink to="/">学习门户</RouterLink>
      <template v-if="session.user"
        ><RouterLink to="/account">个人中心</RouterLink
        ><RouterLink to="/applications">贡献资格</RouterLink
        ><RouterLink
          v-if="session.user.permissions.includes('system:user:manage')"
          to="/admin"
          >管理后台</RouterLink
        ></template
      >
      <template v-else
        ><RouterLink to="/login">登录</RouterLink
        ><RouterLink to="/register" class="nav-action"
          >创建账号</RouterLink
        ></template
      >
    </nav>
  </header>
  <main id="main" class="portal-main" tabindex="-1"><RouterView /></main>
  <footer class="site-footer">
    中医科普学习平台<span>以可信知识，支持持续学习</span>
  </footer>
</template>
