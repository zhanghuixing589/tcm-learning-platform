<script setup lang="ts">
import { onBeforeUnmount, reactive, ref, watch } from "vue";
import type { Page } from "../../shared/api/types";
import { latestRequest } from "../account/personalCenter";
import { communityApi } from "./api";
import { characterCount, communityError } from "./editor";
import type { Post, PostFilter, PostQuery, PostSort, Section } from "./types";
import CommunityPageBar from "./CommunityPageBar.vue";
import CommunityPostCard from "./CommunityPostCard.vue";

const props = defineProps<{ section?: Section }>();
const emit = defineEmits<{ loaded: [result: Page<Post> | null] }>();
const form = reactive({ q: "", type: "ALL" as PostFilter, sort: "NEWEST" as PostSort, includeChildren: true });
const submitted = ref<PostQuery>({});
const list = ref<Page<Post> | null>(null);
const error = ref("");
const loading = ref(false);
const requestedPage = ref(1);
const requests = latestRequest();

async function load(page = 1) {
  const ticket = requests.begin();
  requestedPage.value = page;
  loading.value = true;
  error.value = "";
  emit("loaded", null);
  try {
    const result = await communityApi.posts({ ...submitted.value, sectionId: props.section?.id, page });
    if (requests.isCurrent(ticket)) { list.value = result; emit("loaded", result); }
  } catch (cause) {
    if (requests.isCurrent(ticket)) error.value = communityError(cause);
  } finally {
    if (requests.isCurrent(ticket)) loading.value = false;
  }
}
function selectType(value: PostFilter) {
  form.type = value;
  search();
}
function search() {
  const q = form.q.trim();
  if (characterCount(q) > 100) {
    error.value = "搜索词最多 100 字符。";
    return;
  }
  submitted.value = { q: q || undefined, type: form.type, sort: form.sort, includeChildren: form.includeChildren };
  void load();
}
watch(() => [form.sort, form.includeChildren], () => search());
watch(() => props.section?.id, () => {
  list.value = null;
  search();
}, { immediate: true });
onBeforeUnmount(requests.invalidate);
</script>

<template>
  <section class="community-list" aria-label="社区内容">
    <nav class="community-feed-tabs" aria-label="内容发现类型">
      <button v-for="tab in [{ value: 'ALL', label: '全部内容' }, { value: 'ARTICLE', label: '科普文章' }, { value: 'POST', label: '学习讨论' }]" :key="tab.value"
        type="button" :aria-pressed="(submitted.type || 'ALL') === tab.value" @click="selectType(tab.value as PostFilter)">{{ tab.label }}</button>
    </nav>
    <form class="community-filter" @submit.prevent="search">
      <div class="community-form-grid">
        <label class="community-field">搜索社区内容
          <input v-model="form.q" type="search" placeholder="搜索标题或正文" />
        </label>
        <label class="community-field">排序
          <select v-model="form.sort">
            <option value="NEWEST">最新发布</option>
            <option value="LATEST_REPLY">最近回复</option>
          </select>
        </label>
      </div>
      <div class="community-actions">
        <label v-if="section && !section.parentId" class="community-check">
          <input v-model="form.includeChildren" type="checkbox" /> 包含子分区
        </label>
        <button class="community-primary" type="submit" :disabled="loading">应用筛选</button>
      </div>
    </form>
    <p v-if="loading" role="status" class="community-empty">正在读取社区内容…</p>
    <div v-if="error">
      <p role="alert" class="error-message">{{ error }}</p>
      <button type="button" @click="load(requestedPage)">重新加载</button>
    </div>
    <template v-if="list && !loading && !error">
      <div class="feed-heading"><h2>{{ section ? '分区里的声音' : '发现与讨论' }}</h2><span>{{ list.total }} 条内容</span></div>
      <p v-if="!list.items.length && !loading" class="community-empty">没有符合条件的内容。</p>
      <ul class="community-rows community-discovery-feed">
        <li v-for="item in list.items" :key="item.id">
          <CommunityPostCard :item="item" />
        </li>
      </ul>
      <CommunityPageBar :result="list" :disabled="loading" @change="load" />
    </template>
  </section>
</template>
