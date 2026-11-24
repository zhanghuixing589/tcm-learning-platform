<script setup lang="ts">
import { computed, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useSessionStore } from "./shared/stores/session";
import { canEnter } from "./router/access";
import zhCn from "element-plus/es/locale/lang/zh-cn";
const session = useSessionStore(),
  route = useRoute(),
  router = useRouter();
const allowed = computed(() => canEnter(route.meta, session.user));
watch(allowed, (canView) => {
  if (canView) return;
  void router.replace(session.user
    ? "/forbidden"
    : { path: "/login", query: { redirect: route.fullPath } });
}, { immediate: true });
</script>
<template>
  <el-config-provider :locale="zhCn">
    <RouterView v-if="allowed" :key="session.user?.id ?? 'anonymous'" />
  </el-config-provider>
</template>
