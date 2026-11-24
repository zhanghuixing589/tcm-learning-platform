<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { api, errorMessage } from "../../shared/api/client";
import type { Envelope } from "../../shared/api/types";
import { useSessionStore } from "../../shared/stores/session";
import {
  formatDate,
  latestRequest,
  typeLabels,
  type ResourceDetail,
} from "./personalCenter";
import AccountIcon from "./AccountIcon.vue";
import CommunityReader from "../community/CommunityReader.vue";
import CommunityMarkdown from "../community/CommunityMarkdown.vue";
import "./account.css";
const route = useRoute(),
  item = ref<ResourceDetail | null>(null),
  loading = ref(true),
  error = ref(""),
  actionError = ref(""),
  pending = ref<string[]>([]);
const requests = latestRequest();
const session = useSessionStore();
const community = computed(() => item.value?.community
  ? { ...item.value.community, body: item.value.body }
  : null);
let viewVersion = 0;
async function load() {
  viewVersion++;
  pending.value = [];
  actionError.value = "";
  const ticket = requests.begin();
  loading.value = true;
  error.value = "";
  item.value = null;
  try {
    const response = await api.http.get<Envelope<ResourceDetail>>(
      `/resources/${route.params.resourceId}`,
    );
    if (requests.isCurrent(ticket)) item.value = response.data.data;
  } catch (e) {
    if (requests.isCurrent(ticket)) error.value = errorMessage(e);
  } finally {
    if (requests.isCurrent(ticket)) loading.value = false;
  }
}
async function toggle(kind: "like" | "bookmark") {
  if (!item.value || pending.value.includes(kind)) return;
  const version = viewVersion;
  const current = item.value,
    active = kind === "like" ? current.liked : current.bookmarked;
  pending.value.push(kind);
  actionError.value = "";
  try {
    if (active) await api.http.delete(`/resources/${current.id}/${kind}`);
    else await api.http.put(`/resources/${current.id}/${kind}`);
    if (item.value === current) {
      if (kind === "like") {
        current.liked = !active;
        if (current.ownerId !== session.user?.id)
          current.likeCount = Math.max(
            0,
            current.likeCount + (active ? -1 : 1),
          );
      } else {
        current.bookmarked = !active;
        current.bookmarkCount += active ? -1 : 1;
      }
    }
  } catch (e) {
    if (version === viewVersion) actionError.value = errorMessage(e);
  } finally {
    if (version === viewVersion)
      pending.value = pending.value.filter((value) => value !== kind);
  }
}
watch(() => route.params.resourceId, load, { immediate: true });
onBeforeUnmount(() => {
  viewVersion++;
  requests.invalidate();
});
</script>
<template>
  <div :class="community ? 'community-page' : 'account-subpage'">
    <RouterLink class="account-back" to="/account"
      ><AccountIcon name="back" :size="18" />返回个人中心</RouterLink
    ><el-skeleton v-if="loading" :rows="8" animated />
    <div v-else-if="error" class="library-empty">
      <h1>内容暂不可查看</h1>
      <p>{{ error }}</p>
      <el-button @click="load">重新加载</el-button>
    </div>
    <CommunityReader v-else-if="community" :post="community" />
    <article v-else-if="item">
      <p v-if="item.resourceType === 'KNOWLEDGE'" class="success-message">
        <RouterLink :to="`/knowledge/${item.id}`"
          >进入知识详情，查看结构化知识、来源依据与版本</RouterLink
        >
      </p>
      <header class="resource-heading">
        <span class="entry-type">{{ typeLabels[item.resourceType] }}</span>
        <h1>{{ item.title }}</h1>
        <div class="entry-meta">
          <RouterLink :to="`/users/${item.ownerId}`">{{
            item.ownerNickname
          }}</RouterLink><time :datetime="item.occurredAt">{{
            formatDate(item.occurredAt)
          }}</time>
        </div>
      </header>
      <CommunityMarkdown v-if="item.resourceType === 'ARTICLE' || item.resourceType === 'POST'"
        :body="item.body" source-format="PLAIN_TEXT" />
      <div v-else class="resource-body">{{ item.body }}</div>
      <p v-if="actionError" class="error-message" role="alert">
        {{ actionError }}
      </p>
      <div class="resource-actions">
        <el-button
          :type="item.liked ? 'primary' : 'default'"
          :loading="pending.includes('like')"
          :aria-pressed="item.liked"
          @click="toggle('like')"
          ><AccountIcon name="heart" :size="17" />{{
            item.liked ? "已点赞" : "点赞"
          }}
          {{ item.likeCount }}</el-button
        ><el-button
          :type="item.bookmarked ? 'primary' : 'default'"
          :loading="pending.includes('bookmark')"
          :aria-pressed="item.bookmarked"
          @click="toggle('bookmark')"
          ><AccountIcon name="bookmark" :size="17" />{{
            item.bookmarked ? "已收藏" : "收藏"
          }}</el-button
        >
      </div>
    </article>
  </div>
</template>
