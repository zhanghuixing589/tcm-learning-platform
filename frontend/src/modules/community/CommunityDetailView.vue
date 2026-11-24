<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { latestRequest } from "../account/personalCenter";
import { communityApi } from "./api";
import { communityError } from "./editor";
import type { Post } from "./types";
import CommunityReader from "./CommunityReader.vue";
import "../account/account.css";
import "./community.css";

const route = useRoute();
const item = ref<Post | null>(null);
const loading = ref(false);
const error = ref("");
const requests = latestRequest();
async function load() {
  const ticket = requests.begin();
  item.value = null;
  loading.value = true;
  error.value = "";
  try {
    const value = await communityApi.post(String(route.params.resourceId));
    if (requests.isCurrent(ticket)) item.value = value;
  } catch (cause) {
    if (requests.isCurrent(ticket)) error.value = communityError(cause);
  } finally {
    if (requests.isCurrent(ticket)) loading.value = false;
  }
}
watch(() => route.params.resourceId, load, { immediate: true });
onBeforeUnmount(requests.invalidate);
</script>

<template>
  <div class="community-page">
    <RouterLink class="community-back" to="/community">返回科普社区</RouterLink>
    <p v-if="loading" role="status">正在读取正文…</p>
    <div v-else-if="error">
      <h1>内容暂不可查看</h1>
      <p role="alert" class="error-message">{{ error }}</p>
      <button type="button" @click="load">重新加载</button>
    </div>
    <CommunityReader v-else-if="item" :post="item" />
  </div>
</template>
