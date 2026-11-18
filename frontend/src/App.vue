<script setup lang="ts">
import { watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useSessionStore } from "./shared/stores/session";
import zhCn from "element-plus/es/locale/lang/zh-cn";
const session = useSessionStore(),
  route = useRoute(),
  router = useRouter();
watch(
  () => session.user,
  (user) => {
    if (!user && route.meta.requiresAuth)
      void router.replace({
        path: "/login",
        query: { redirect: route.fullPath },
      });
  },
);
</script>
<template>
  <el-config-provider :locale="zhCn"><RouterView /></el-config-provider>
</template>
