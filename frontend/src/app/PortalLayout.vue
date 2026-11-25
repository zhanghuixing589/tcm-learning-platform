<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { useSessionStore } from "../shared/stores/session";
const session = useSessionStore();
const route = useRoute();
const menuOpen = ref(false);
const workspaceMenu = ref<HTMLDetailsElement>();
const menuToggle = ref<HTMLButtonElement>();
const header = ref<HTMLElement>();
let headerSize: ResizeObserver | undefined;
function closeMenus() { menuOpen.value = false; if (workspaceMenu.value) workspaceMenu.value.open = false; }
function dismissMenus() {
  if (menuOpen.value) menuToggle.value?.focus();
  else if (workspaceMenu.value?.open) workspaceMenu.value.querySelector("summary")?.focus();
  closeMenus();
}
function onPointerDown(event: PointerEvent) {
  if (event.target instanceof Node && !workspaceMenu.value?.contains(event.target) && workspaceMenu.value) workspaceMenu.value.open = false;
}
onMounted(() => {
  document.addEventListener("pointerdown", onPointerDown);
  const measure = () => document.documentElement.style.setProperty('--portal-header-height', `${header.value?.getBoundingClientRect().height || 88}px`);
  measure();
  if (typeof ResizeObserver !== 'undefined' && header.value) {
    headerSize = new ResizeObserver(measure);
    headerSize.observe(header.value);
  }
});
onBeforeUnmount(() => { document.removeEventListener("pointerdown", onPointerDown); headerSize?.disconnect(); document.documentElement.style.removeProperty('--portal-header-height'); });
watch(() => route.fullPath, closeMenus);
</script>
<template>
  <a class="skip-link" href="#main">跳到主要内容</a>
  <header ref="header" class="site-header">
    <RouterLink to="/" class="brand"
      ><span class="brand-mark" aria-hidden="true"><svg viewBox="0 0 32 32" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M16 27V13M16 21C6 21 5 12 5 12s11-1 11 9ZM16 15C16 5 27 5 27 5s1 11-11 10Z"/></svg></span
      ><span
        >中医科普<span class="brand-subtitle">本草有知 · 学习有据</span></span
      ></RouterLink
    >
    <button ref="menuToggle" class="nav-toggle" type="button" aria-controls="portal-navigation" :aria-expanded="menuOpen"
      @click="menuOpen = !menuOpen" @keydown.esc="menuOpen = false">{{ menuOpen ? '收起导航' : '浏览导航' }} <span aria-hidden="true">{{ menuOpen ? '−' : '☰' }}</span></button>
    <nav id="portal-navigation" aria-label="主要导航" :class="{ 'is-open': menuOpen }" @keydown.esc.prevent="dismissMenus">
      <RouterLink to="/">学习门户</RouterLink>
      <RouterLink to="/knowledge">知识中心</RouterLink>
      <RouterLink to="/community">科普社区</RouterLink>
      <template v-if="session.user"
        ><details ref="workspaceMenu" class="nav-workspace"><summary>工作空间</summary><div class="workspace-links"><RouterLink to="/applications">贡献资格</RouterLink
        ><RouterLink to="/contribute">贡献工作台</RouterLink
        ><RouterLink
          v-if="
            session.user.permissions.some((p) =>
              ['knowledge:review', 'community:moderate'].includes(p),
            )
          "
          to="/reviews"
          >内容审核</RouterLink
        ><RouterLink
          v-if="session.user.permissions.includes('system:job:manage')"
          to="/admin/jobs"
          >技术任务</RouterLink
        ><RouterLink
          v-if="session.user.permissions.some((permission) =>
            ['knowledge:taxonomy:manage', 'system:user:manage', 'community:section:manage', 'community:quota:manage'].includes(permission))"
          to="/admin"
          >管理后台</RouterLink
        ></div></details><RouterLink to="/account" class="nav-profile"><span class="nav-avatar" aria-hidden="true">{{ Array.from(session.user.nickname || '学')[0] }}</span>个人中心</RouterLink></template
      >
      <template v-else
        ><RouterLink to="/login">登录</RouterLink
        ><RouterLink to="/register" class="nav-action"
          >创建账号</RouterLink
        ></template
      >
    </nav>
  </header>
  <main id="main" class="portal-main" tabindex="-1" @focusin="closeMenus"><RouterView /></main>
  <footer class="site-footer">
    <RouterLink class="footer-brand" to="/">中医科普学习平台</RouterLink><span>一草一木，皆有学问。以可信知识，支持持续学习。</span><RouterLink to="/contribute">参与知识共建 ↗</RouterLink>
  </footer>
</template>
