<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { api } from "../../shared/api/client";
import { useSessionStore } from "../../shared/stores/session";
import { formatDate, latestRequest } from "../account/personalCenter";
import UserAvatar from "../account/UserAvatar.vue";
import { communityApi } from "./api";
import { communityError } from "./editor";
import type { Post, TextPolicy } from "./types";
import CommentThread from "./CommentThread.vue";
import CommunityMarkdown from "./CommunityMarkdown.vue";
import CommunityReferences from "./CommunityReferences.vue";
import SectionPanel from "./SectionPanel.vue";
import "./community.css";
import "./community-experience.css";
import CommunityRelated from "./CommunityRelated.vue";
import type { CommunityHeading } from "./reading";
import CommunityOutline from "./CommunityOutline.vue";
import CommunityGlyph from "./CommunityGlyph.vue";

const props = defineProps<{ post: Post }>();
const item = ref({ ...props.post });
const showUpdated = computed(() => !!item.value.publicEditedAt && formatDate(item.value.publicEditedAt) !== formatDate(item.value.publishedAt));
const session = useSessionStore();
const policy = ref<TextPolicy | null>(null);
const policyError = ref("");
const policyRequests = latestRequest();
const pending = ref<("like" | "bookmark" | "pin")[]>([]);
const error = ref("");
const headings = ref<CommunityHeading[]>([]);
const commentCountError = ref(false);
const commentCounts = latestRequest();
let epoch = 0;

async function refreshCommentCount() {
  const ticket = commentCounts.begin();
  const id = item.value.id;
  commentCountError.value = false;
  try {
    const value = await communityApi.post(id);
    if (commentCounts.isCurrent(ticket) && item.value.id === id) item.value.commentCount = value.commentCount;
  } catch {
    if (commentCounts.isCurrent(ticket)) commentCountError.value = true;
  }
}

async function loadPolicy() {
  const ticket = policyRequests.begin();
  policyError.value = "";
  try {
    const result = await communityApi.textPolicy();
    if (policyRequests.isCurrent(ticket)) policy.value = result;
  } catch (cause) {
    if (policyRequests.isCurrent(ticket)) policyError.value = communityError(cause);
  }
}
async function toggle(kind: "like" | "bookmark" | "pin") {
  if (pending.value.includes(kind) || (kind === "pin" && !item.value.capabilities.canPin)) return;
  const current = epoch;
  const value = item.value;
  pending.value.push(kind);
  error.value = "";
  try {
    if (kind === "pin") {
      const result = await communityApi.pin(value.section.id, value.id, { pinned: !value.pinned, lockVersion: value.pinLockVersion });
      if (current === epoch) {
        value.pinned = result.pinned;
        value.pinLockVersion = result.lockVersion;
      }
      return;
    }
    const active = kind === "like" ? value.liked : value.bookmarked;
    const path = "/resources/" + value.id + "/" + kind;
    if (active) await api.http.delete(path);
    else await api.http.put(path);
    if (current !== epoch) return;
    if (kind === "like") {
      value.liked = !active;
      if (value.owner.id !== session.user?.id) value.likeCount = Math.max(0, value.likeCount + (active ? -1 : 1));
    } else {
      value.bookmarked = !active;
      value.bookmarkCount = Math.max(0, value.bookmarkCount + (active ? -1 : 1));
    }
  } catch (cause) {
    if (current === epoch) error.value = communityError(cause);
  } finally {
    if (current === epoch) pending.value = pending.value.filter((value) => value !== kind);
  }
}
watch(() => props.post, (value) => {
  epoch++;
  item.value = { ...value };
  headings.value = [];
  commentCounts.invalidate();
  commentCountError.value = false;
  pending.value = [];
  error.value = "";
  void loadPolicy();
}, { immediate: true });
onBeforeUnmount(() => { epoch++; policyRequests.invalidate(); commentCounts.invalidate(); });
</script>

<template>
  <div class="community-reading community-experience-reader" :class="{ 'reader-discussion': item.resourceType === 'POST' }">
    <article class="community-reader-content">
      <header class="community-article-header">
        <div class="community-meta reader-kicker">
          <span class="community-tag">{{ item.resourceType === 'ARTICLE' ? '科普文章' : '学习讨论' }}</span>
          <span v-if="item.pinned">置顶</span>
          <RouterLink v-if="item.section.code !== 'legacy-content'" :to="'/community/sections/' + item.section.id">{{ item.section.name }}</RouterLink>
          <span v-else>{{ item.section.name }}</span>
        </div>
        <h1>{{ item.title }}</h1>
        <div class="reader-author-line">
          <UserAvatar :nickname="item.owner.nickname" :url="item.owner.avatarUrl" />
          <div><RouterLink :to="'/users/' + item.owner.id">{{ item.owner.nickname }}</RouterLink><p><time :datetime="item.publishedAt">{{ formatDate(item.publishedAt) }}</time><span v-if="showUpdated"> · 更新于 {{ formatDate(item.publicEditedAt!) }}</span></p></div>
          <a class="reader-comment-jump" :href="'#community-comments-' + item.id">{{ commentCountError ? '评论数待更新' : item.commentCount + ' 条评论' }} ↘</a>
        </div>
      </header>
      <CommunityOutline v-if="headings.length" :headings="headings" class="reader-toc-mobile" />
      <div v-if="policyError && item.sourceFormat === 'MARKDOWN'">
        <p role="alert" class="error-message">暂以源文本显示。{{ policyError }}</p>
        <button type="button" @click="loadPolicy">重新加载排版规则</button>
      </div>
      <div class="community-read-body">
        <CommunityMarkdown :body="item.body ?? ''" :source-format="item.sourceFormat" :references="item.references" :policy="policy" outline @outline="headings = $event" />
        <CommunityReferences :references="item.references" :policy="policy" />
      </div>
      <p v-if="error" role="alert" class="error-message">{{ error }}</p>
      <p v-if="commentCountError" role="status" class="muted">评论已处理，计数暂未更新。<button type="button" @click="refreshCommentCount">重新加载评论数</button></p>
      <div class="community-actions reader-engagement" aria-label="内容互动">
        <button type="button" :aria-pressed="item.liked" :disabled="pending.includes('like')" @click="toggle('like')"><CommunityGlyph kind="like" />{{ item.liked ? '已点赞' : '点赞' }} {{ item.likeCount }}</button>
        <button type="button" :aria-pressed="item.bookmarked" :disabled="pending.includes('bookmark')" @click="toggle('bookmark')"><CommunityGlyph kind="bookmark" />{{ item.bookmarked ? '已收藏' : '收藏' }} {{ item.bookmarkCount }}</button>
        <a :href="'#community-comments-' + item.id" class="community-button"><CommunityGlyph kind="comment" />{{ item.resourceType === 'POST' ? '参与讨论' : '交流想法' }} ↘</a>
        <button v-if="item.capabilities.canPin" type="button" :disabled="pending.includes('pin')" @click="toggle('pin')">{{ item.pinned ? '取消置顶' : '在本区置顶' }}</button>
        <RouterLink v-if="item.capabilities.canEdit" class="community-button" :to="'/account/posts/' + item.id">编辑讨论</RouterLink>
      </div>
      <CommentThread :id="'community-comments-' + item.id" :key="item.id" :resource-id="item.id" :can-comment="item.capabilities.canComment" :comments-enabled="item.commentsEnabled" :policy="policy" @changed="refreshCommentCount" />
    </article>
    <aside class="community-reader-rail" aria-label="作者与延伸阅读">
      <section class="reader-author-card"><p class="community-rail-eyebrow">{{ item.resourceType === 'ARTICLE' ? '这篇分享来自' : '讨论发起人' }}</p><UserAvatar :nickname="item.owner.nickname" :url="item.owner.avatarUrl" /><h2><RouterLink :to="'/users/' + item.owner.id">{{ item.owner.nickname }}</RouterLink></h2><RouterLink :to="'/users/' + item.owner.id">看看 TA 的公开分享 →</RouterLink></section>
      <CommunityOutline v-if="headings.length" :headings="headings" expanded class="reader-toc-desktop" />
      <SectionPanel v-if="item.section.code !== 'legacy-content'" :key="item.section.id" :section-id="item.section.id" />
      <CommunityRelated :resource-id="item.id" :section-id="item.section.code !== 'legacy-content' ? item.section.id : undefined" />
    </aside>
  </div>
</template>
