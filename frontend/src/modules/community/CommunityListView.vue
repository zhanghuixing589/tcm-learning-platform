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
import CommunityCompanion from "./CommunityCompanion.vue";
import type { Post } from "./types";

const session = useSessionStore();
const groups = ref<SectionGroup[]>([]);
const pending = ref(true);
const error = ref("");
const posts = ref<Post[]>([]);
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
  <div class="community-page community-discovery">
    <div class="community-discovery-layout">
      <div class="community-navigation-column">
        <p v-if="pending" role="status">正在读取分区…</p>
        <template v-else-if="error">
          <p class="error-message" role="alert">{{ error }}</p>
          <button type="button" @click="load">重新加载分区</button>
        </template>
        <SectionNavigation v-else :groups="groups" />
      </div>
      <div class="community-discovery-main">
        <header id="community-participate" class="community-welcome">
          <p class="community-eyebrow">科普社区 / 本草之间，与你共谈</p>
          <h1>让所学，在交流中生长。</h1>
          <p>一份阅读笔记，一个尚未想明白的问题。<br />在这里分享，也在彼此的见解中继续学习。</p>
          <div class="community-actions">
            <RouterLink v-if="session.user?.permissions.includes('community:post')" to="/community/posts/new" class="community-button community-primary">＋ 发起讨论</RouterLink>
            <RouterLink v-if="session.user?.permissions.includes('community:article:submit')" to="/account/articles/new" class="community-button">撰写科普文章 ↗</RouterLink>
            <RouterLink v-else to="/applications" class="community-contributor-link">成为内容贡献者 →</RouterLink>
          </div>
        </header>
        <PostFeed @loaded="posts = $event?.items || []" />
      </div>
      <CommunityCompanion :groups="groups" :posts="posts" />
    </div>
  </div>
</template>
