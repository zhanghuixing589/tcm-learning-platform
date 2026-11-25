<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useSessionStore } from "../../shared/stores/session";
import { stageApi, typeLabels, type KnowledgeSummary } from "../knowledge/api";
import { communityApi } from "../community/api";
import type { Post } from "../community/types";
import { communityExcerpt } from "../community/reading";
import UserAvatar from "./UserAvatar.vue";
import { latestRequest } from "./personalCenter";
import ContentGlyph from "../../app/ui/ContentGlyph.vue";
const session = useSessionStore();
const knowledge = ref<KnowledgeSummary[]>([]), posts = ref<Post[]>([]);
const postPreviews = computed(() => posts.value.map(post => ({
  ...post,
  excerpt: communityExcerpt(post.summary || "", post.sourceFormat),
})));
const knowledgePending = ref(false), communityPending = ref(false);
const knowledgeError = ref(false), communityError = ref(false);
const knowledgeRequests = latestRequest(), communityRequests = latestRequest();
async function loadKnowledge() {
  const ticket = knowledgeRequests.begin();
  knowledge.value = []; knowledgeError.value = false;
  knowledgePending.value = !!session.user;
  if (!session.user) return;
  try {
    const result = await stageApi.knowledge({ page: 1 });
    if (knowledgeRequests.isCurrent(ticket)) knowledge.value = result.items.slice(0, 3);
  } catch {
    if (knowledgeRequests.isCurrent(ticket)) knowledgeError.value = true;
  } finally {
    if (knowledgeRequests.isCurrent(ticket)) knowledgePending.value = false;
  }
}
async function loadCommunity() {
  const ticket = communityRequests.begin();
  posts.value = []; communityError.value = false;
  communityPending.value = !!session.user;
  if (!session.user) return;
  try {
    const result = await communityApi.posts({ page: 1, sort: "NEWEST" });
    if (communityRequests.isCurrent(ticket)) posts.value = result.items.slice(0, 2);
  } catch {
    if (communityRequests.isCurrent(ticket)) communityError.value = true;
  } finally {
    if (communityRequests.isCurrent(ticket)) communityPending.value = false;
  }
}
watch(() => session.user?.id, () => { void loadKnowledge(); void loadCommunity(); }, { immediate: true });
onBeforeUnmount(() => { knowledgeRequests.invalidate(); communityRequests.invalidate(); });
</script>
<template>
  <section class="home-discover" aria-labelledby="discover-title">
    <div class="section-heading"><div><p class="eyebrow">翻开知识的一页</p><h2 id="discover-title">在阅读中，让理解更深一点</h2></div><RouterLink to="/knowledge">进入知识中心 →</RouterLink></div>
    <div v-if="!session.user" class="discovery-invitation"><ContentGlyph kind="CONCEPT" /><div><h3>从可靠的知识，开始你的探索</h3><p>登录后阅读已审核的药材、方剂与中医知识，收藏感兴趣的内容，也可以沿依据追溯原文。</p><RouterLink to="/login?redirect=/knowledge">登录，开启阅读 →</RouterLink></div></div>
    <div v-else class="discovery-columns" :class="{ 'discovery-columns-split': knowledge.length > 1 && posts.length > 0 }">
      <section class="discovery-knowledge" aria-labelledby="knowledge-preview-title" :aria-busy="knowledgePending">
        <h3 id="knowledge-preview-title" class="column-heading">知识选读 <span>正式版本 · 有据可查</span></h3>
        <p v-if="knowledgePending" class="home-content-state" role="status">正在加载知识选读…</p>
        <div v-else-if="knowledgeError" class="home-content-state"><p role="alert">知识内容暂未加载，请稍后重试。</p><button class="text-button" @click="loadKnowledge">重新加载知识</button><RouterLink to="/knowledge">前往知识中心 →</RouterLink></div>
        <div v-else-if="!knowledge.length" class="home-content-state"><div><h4>知识目录正在积累</h4><p>已审核的正式知识将在这里与你见面，先去目录看看，或到社区交流学习发现。</p></div><RouterLink to="/knowledge">浏览知识目录 →</RouterLink></div>
        <div v-else class="home-knowledge-list" :class="{ 'home-knowledge-grid': !posts.length }">
          <article v-for="item in knowledge" :key="item.id" class="discovery-entry">
            <div class="discovery-entry-heading"><ContentGlyph :kind="item.type" /><small>{{ typeLabels[item.type] }}<template v-if="item.categoryName"> · {{ item.categoryName }}</template><span> · 第 {{ item.versionNo }} 版</span></small></div>
            <h3><RouterLink :to="'/knowledge/' + item.id">{{ item.title }}</RouterLink></h3>
            <p>{{ item.summary || '阅读知识正文，了解结构化事实与来源依据。' }}</p>
            <RouterLink class="home-source-link" :to="'/knowledge/' + item.id + '#knowledge-read-evidence'">查看来源依据 <span aria-hidden="true">↗</span></RouterLink>
          </article>
        </div>
      </section>
      <section class="discovery-community" aria-labelledby="community-preview-title" :aria-busy="communityPending">
        <h3 id="community-preview-title" class="column-heading">社区新声 <RouterLink to="/community">浏览社区 →</RouterLink></h3>
        <p v-if="communityPending" class="home-content-state" role="status">正在加载社区新声…</p>
        <div v-else-if="communityError" class="home-content-state"><p role="alert">社区内容暂未加载，请稍后重试。</p><button class="text-button" @click="loadCommunity">重新加载社区</button><RouterLink to="/community">前往科普社区 →</RouterLink></div>
        <div v-else-if="!posts.length" class="home-content-state"><p>还没有公开内容，来分享你的学习发现。</p><RouterLink to="/community">走进科普社区 →</RouterLink></div>
        <div v-else class="home-community-list">
          <article v-for="post in postPreviews" :key="post.id">
            <small>{{ post.section.name }} · {{ post.resourceType === 'ARTICLE' ? '科普文章' : '讨论' }}</small>
            <h3><RouterLink :to="'/community/posts/' + post.id">{{ post.title }}</RouterLink></h3>
            <p v-if="post.excerpt" class="home-post-summary">{{ post.excerpt }}</p>
            <RouterLink class="home-post-author" :to="'/users/' + post.owner.id"><UserAvatar :nickname="post.owner.nickname" :url="post.owner.avatarUrl" />{{ post.owner.nickname }}</RouterLink>
            <p class="home-post-stats"><RouterLink :to="'/community/posts/' + post.id + '#community-comments-' + post.id">{{ post.commentCount }} 条评论</RouterLink><span>{{ post.likeCount }} 个赞</span><span v-if="post.resourceType === 'ARTICLE'">{{ post.bookmarkCount }} 人收藏</span></p>
          </article>
        </div>
      </section>
    </div>
  </section>
</template>
