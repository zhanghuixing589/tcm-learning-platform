<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from "vue";
import { communityApi } from "./api";
import type { Post } from "./types";
import { latestRequest } from "../account/personalCenter";
const props = defineProps<{ resourceId: string; sectionId?: string }>();
const items = ref<Post[]>([]);
const pending = ref(false), failed = ref(false);
const requests = latestRequest();
async function load() {
  const ticket = requests.begin();
  items.value = []; pending.value = true; failed.value = false;
  try {
    const result = await communityApi.posts({ sectionId: props.sectionId, sort: 'NEWEST', page: 1, pageSize: 4 });
    if (requests.isCurrent(ticket)) items.value = result.items.filter(item => item.id !== props.resourceId).slice(0, 3);
  } catch {
    if (requests.isCurrent(ticket)) failed.value = true;
  } finally {
    if (requests.isCurrent(ticket)) pending.value = false;
  }
}
watch(() => [props.resourceId, props.sectionId], load, { immediate: true });
onBeforeUnmount(requests.invalidate);
</script>
<template>
  <section class="community-related">
    <h2>{{ sectionId ? '同区延伸阅读' : '继续发现' }}</h2>
    <p v-if="pending" role="status">正在寻找更多分享…</p>
    <template v-else-if="failed"><p>延伸内容暂未加载。</p><button class="text-button" @click="load">重新加载</button></template>
    <p v-else-if="!items.length">这里还没有更多公开分享。回到社区，看看其他分区。</p>
    <ol v-else><li v-for="(item, index) in items" :key="item.id"><span class="related-index">0{{ index + 1 }}</span><div><small>{{ item.resourceType === 'ARTICLE' ? '科普文章' : '学习讨论' }}</small><h3><RouterLink :to="'/community/posts/' + item.id">{{ item.title }}</RouterLink></h3><p>{{ item.owner.nickname }} · {{ item.commentCount }} 条评论</p></div></li></ol>
    <RouterLink :to="sectionId ? '/community/sections/' + sectionId : '/community'">{{ sectionId ? '浏览这个分区' : '返回社区发现' }} →</RouterLink>
  </section>
</template>
