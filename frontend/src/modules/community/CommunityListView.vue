<script setup lang="ts">
import { onBeforeUnmount, ref } from "vue";
import { useSessionStore } from "../../shared/stores/session";
import { latestRequest } from "../account/personalCenter";
import { sectionTree, type SectionGroup } from "./api";
import { communityError } from "./editor";
import PostFeed from "./PostFeed.vue";
import SectionNavigation from "./SectionNavigation.vue";
import "../account/account.css";
import "./community.css";

const session = useSessionStore();
const groups = ref<SectionGroup[]>([]);
const pending = ref(true);
const error = ref("");
const requests = latestRequest();
async function load() {
  const ticket = requests.begin();
  pending.value = true;
  error.value = "";
  try {
    const result = await sectionTree();
    if (requests.isCurrent(ticket)) groups.value = result;
  } catch (cause) {
    if (requests.isCurrent(ticket)) error.value = communityError(cause);
  } finally {
    if (requests.isCurrent(ticket)) pending.value = false;
  }
}
void load();
onBeforeUnmount(requests.invalidate);
</script>

<template>
  <div class="community-page">
    <header class="community-heading">
      <div><h1>科普社区</h1><p>按分区阅读学习讨论与科普文章。</p></div>
      <div class="community-actions">
        <RouterLink v-if="session.user?.permissions.includes('community:post')" to="/community/posts/new"
          class="community-button community-primary">发布讨论</RouterLink>
        <RouterLink v-if="session.user?.permissions.includes('community:article:submit')" to="/account/articles/new"
          class="community-button">撰写科普文章</RouterLink>
      </div>
    </header>
    <div class="community-layout">
      <div>
        <p v-if="pending" role="status">正在读取分区…</p>
        <template v-else-if="error">
          <p class="error-message" role="alert">{{ error }}</p>
          <button type="button" @click="load">重新加载分区</button>
        </template>
        <SectionNavigation v-else :groups="groups" />
      </div>
      <PostFeed />
    </div>
  </div>
</template>
