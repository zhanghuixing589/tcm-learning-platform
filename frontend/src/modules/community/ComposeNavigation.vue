<script setup lang="ts">
import { useSessionStore } from "../../shared/stores/session";
import "./community-experience.css";
defineProps<{ kind: 'ARTICLE' | 'POST'; editing?: boolean }>();
const session = useSessionStore();
</script>
<template>
  <nav v-if="!editing" class="compose-type-nav" aria-label="发布内容类型">
    <RouterLink v-if="session.user?.permissions.includes('community:post')" to="/community/posts/new" :aria-current="kind === 'POST' ? 'page' : undefined"><strong>发起讨论</strong><span>带着问题，交流想法</span></RouterLink>
    <RouterLink v-if="session.user?.permissions.includes('community:article:submit')" to="/account/articles/new" :aria-current="kind === 'ARTICLE' ? 'page' : undefined"><strong>撰写文章</strong><span>整理所学，分享见解</span></RouterLink>
  </nav>
</template>
