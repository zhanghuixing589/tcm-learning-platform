<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from "vue";
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

const props = defineProps<{ post: Post }>();
const item = ref({ ...props.post });
const session = useSessionStore();
const policy = ref<TextPolicy | null>(null);
const policyError = ref("");
const policyRequests = latestRequest();
const pending = ref<("like" | "bookmark" | "pin")[]>([]);
const error = ref("");
let epoch = 0;

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
  pending.value = [];
  error.value = "";
  void loadPolicy();
}, { immediate: true });
onBeforeUnmount(() => { epoch++; policyRequests.invalidate(); });
</script>

<template>
  <div class="community-reading">
    <article class="community-reader-content">
      <div class="community-meta">
        <span class="community-tag">{{ item.resourceType === 'ARTICLE' ? '科普文章' : '讨论' }}</span>
        <span v-if="item.pinned">置顶</span>
        <RouterLink v-if="item.section.code !== 'legacy-content'" :to="'/community/sections/' + item.section.id">{{ item.section.name }}</RouterLink>
        <span v-else>{{ item.section.name }}</span>
      </div>
      <h1>{{ item.title }}</h1>
      <div class="community-meta">
        <UserAvatar :nickname="item.owner.nickname" :url="item.owner.avatarUrl" />
        <RouterLink :to="'/users/' + item.owner.id">{{ item.owner.nickname }}</RouterLink>
        <time :datetime="item.publishedAt">{{ formatDate(item.publishedAt) }}</time>
        <span v-if="item.publicEditedAt">更新于 {{ formatDate(item.publicEditedAt) }}</span>
      </div>
      <div v-if="policyError && item.sourceFormat === 'MARKDOWN'">
        <p role="alert" class="error-message">暂以源文本显示。{{ policyError }}</p>
        <button type="button" @click="loadPolicy">重新加载排版规则</button>
      </div>
      <CommunityMarkdown :body="item.body ?? ''" :source-format="item.sourceFormat" :references="item.references" :policy="policy" />
      <CommunityReferences :references="item.references" :policy="policy" />
      <p v-if="error" role="alert" class="error-message">{{ error }}</p>
      <div class="community-actions">
        <button type="button" :aria-pressed="item.liked" :disabled="pending.includes('like')" @click="toggle('like')">
          {{ item.liked ? '已点赞' : '点赞' }} {{ item.likeCount }}
        </button>
        <button type="button" :aria-pressed="item.bookmarked" :disabled="pending.includes('bookmark')" @click="toggle('bookmark')">
          {{ item.bookmarked ? '已收藏' : '收藏' }}
        </button>
        <button v-if="item.capabilities.canPin" type="button" :disabled="pending.includes('pin')" @click="toggle('pin')">
          {{ item.pinned ? '取消置顶' : '在本区置顶' }}
        </button>
        <RouterLink v-if="item.capabilities.canEdit" class="community-button" :to="'/account/posts/' + item.id">编辑讨论</RouterLink>
      </div>
      <CommentThread :key="item.id" :resource-id="item.id" :can-comment="item.capabilities.canComment"
        :comments-enabled="item.commentsEnabled" :policy="policy" />
    </article>
    <SectionPanel v-if="item.section.code !== 'legacy-content'" :key="item.section.id" :section-id="item.section.id" />
  </div>
</template>
