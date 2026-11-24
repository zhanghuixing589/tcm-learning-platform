<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { latestRequest } from "../account/personalCenter";
import { communityApi, sectionTree, type SectionGroup } from "./api";
import { communityError } from "./editor";
import type { Section } from "./types";
import PostFeed from "./PostFeed.vue";
import SectionNavigation from "./SectionNavigation.vue";
import SectionPanel from "./SectionPanel.vue";
import "../account/account.css";
import "./community.css";

const route = useRoute();
const section = ref<Section | null>(null);
const groups = ref<SectionGroup[]>([]);
const loading = ref(true);
const error = ref("");
const navigationError = ref("");
const requests = latestRequest();

async function refresh() {
  const ticket = requests.begin();
  const id = String(route.params.sectionId);
  section.value = null;
  loading.value = true;
  error.value = navigationError.value = "";
  const [detail, navigation] = await Promise.allSettled([communityApi.section(id), sectionTree()]);
  if (!requests.isCurrent(ticket)) return;
  if (detail.status === "fulfilled") section.value = detail.value;
  else error.value = communityError(detail.reason);
  if (navigation.status === "fulfilled") groups.value = navigation.value;
  else navigationError.value = communityError(navigation.reason);
  loading.value = false;
}
function update(value: Section) {
  if (section.value?.id !== value.id) return;
  section.value = value;
  groups.value = groups.value.map((group) => ({
    section: group.section.id === value.id ? value : group.section,
    children: group.children.map((item) => item.id === value.id ? value : item),
  }));
}
watch(() => route.params.sectionId, refresh, { immediate: true });
onBeforeUnmount(requests.invalidate);
</script>

<template>
  <div class="community-page">
    <RouterLink class="community-back" to="/community">返回科普社区</RouterLink>
    <p v-if="loading" role="status">正在读取分区…</p>
    <div v-if="error">
      <h1>分区暂不可查看</h1>
      <p role="alert" class="error-message">{{ error }}</p>
      <button type="button" @click="refresh">重新加载</button>
    </div>
    <template v-if="section">
      <header class="community-heading">
        <div><h1>{{ section.name }}</h1><p v-if="!section.writable">只读分区</p></div>
        <div class="community-actions">
          <RouterLink v-if="section.capabilities.canPost"
            :to="{ path: '/community/posts/new', query: { section: section.id } }"
            class="community-button community-primary">在本区发布讨论</RouterLink>
          <RouterLink v-if="section.capabilities.canSubmitArticle"
            :to="{ path: '/account/articles/new', query: { section: section.id } }"
            class="community-button">向本区投稿</RouterLink>
        </div>
      </header>
      <div class="community-layout">
        <div>
          <p v-if="navigationError" role="alert" class="error-message">{{ navigationError }}</p>
          <SectionNavigation v-else :groups="groups" />
        </div>
        <div>
          <SectionPanel :key="section.id" :section-id="section.id" :initial="section" @updated="update" />
          <PostFeed :key="'feed-' + section.id" :section="section" class="community-section-feed" />
        </div>
      </div>
    </template>
  </div>
</template>
