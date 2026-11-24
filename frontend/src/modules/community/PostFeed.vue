<script setup lang="ts">
import { onBeforeUnmount, reactive, ref, watch } from "vue";
import type { Page } from "../../shared/api/types";
import { formatDate, latestRequest } from "../account/personalCenter";
import UserAvatar from "../account/UserAvatar.vue";
import { communityApi } from "./api";
import { characterCount, communityError } from "./editor";
import type { Post, PostFilter, PostQuery, PostSort, Section } from "./types";
import CommunityPageBar from "./CommunityPageBar.vue";

const props = defineProps<{ section?: Section }>();
const form = reactive({ q: "", type: "ALL" as PostFilter, sort: "NEWEST" as PostSort, includeChildren: true });
const submitted = ref<PostQuery>({});
const list = ref<Page<Post> | null>(null);
const error = ref("");
const loading = ref(false);
const requests = latestRequest();

async function load(page = 1) {
  const ticket = requests.begin();
  loading.value = true;
  error.value = "";
  try {
    const result = await communityApi.posts({ ...submitted.value, sectionId: props.section?.id, page });
    if (requests.isCurrent(ticket)) list.value = result;
  } catch (cause) {
    if (requests.isCurrent(ticket)) error.value = communityError(cause);
  } finally {
    if (requests.isCurrent(ticket)) loading.value = false;
  }
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
watch(() => props.section?.id, () => {
  list.value = null;
  search();
}, { immediate: true });
onBeforeUnmount(requests.invalidate);
</script>

<template>
  <section class="community-list" aria-label="社区内容">
    <form class="community-filter" @submit.prevent="search">
      <div class="community-form-grid">
        <label class="community-field">搜索社区内容
          <input v-model="form.q" type="search" placeholder="搜索标题或正文" />
        </label>
        <label class="community-field">内容类型
          <select v-model="form.type">
            <option value="ALL">全部</option>
            <option value="POST">讨论</option>
            <option value="ARTICLE">科普文章</option>
          </select>
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
      <button type="button" @click="load(list?.page || 1)">重新加载</button>
    </div>
    <template v-if="list">
      <p v-if="!list.items.length && !loading" class="community-empty">没有符合条件的内容。</p>
      <ul class="community-rows">
        <li v-for="item in list.items" :key="item.id">
          <article class="community-row">
            <div class="community-meta">
              <span class="community-tag">{{ item.resourceType === 'ARTICLE' ? '科普文章' : '讨论' }}</span>
              <span v-if="item.pinned">置顶</span>
              <RouterLink v-if="item.section.code !== 'legacy-content'" :to="'/community/sections/' + item.section.id">{{ item.section.name }}</RouterLink>
              <span v-else>{{ item.section.name }}</span>
            </div>
            <h3><RouterLink :to="'/community/posts/' + item.id">{{ item.title }}</RouterLink></h3>
            <p v-if="item.summary">{{ item.summary }}</p>
            <div class="community-meta">
              <UserAvatar :nickname="item.owner.nickname" :url="item.owner.avatarUrl" />
              <RouterLink :to="'/users/' + item.owner.id">{{ item.owner.nickname }}</RouterLink>
              <time :datetime="item.publishedAt">{{ formatDate(item.publishedAt) }}</time>
              <span>{{ item.commentCount }} 条评论</span><span>{{ item.likeCount }} 个赞</span>
            </div>
          </article>
        </li>
      </ul>
      <CommunityPageBar :result="list" :disabled="loading" @change="load" />
    </template>
  </section>
</template>
