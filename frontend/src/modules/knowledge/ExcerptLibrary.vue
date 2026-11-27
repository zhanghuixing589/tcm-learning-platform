<script setup lang="ts">
import { ref } from "vue";
import { readerApi, type Excerpt } from "./readerApi";
import { errorMessage } from "../../shared/api/client";
import { evidenceReaderLink } from "./readerSelection";
const emit = defineEmits<{ add: [excerpt: Excerpt] }>();
const items = ref<Excerpt[]>([]),
  error = ref(""),
  busy = ref(false),
  loaded = ref(false);
async function load() {
  busy.value = true;
  error.value = "";
  try {
    items.value = await readerApi.personalExcerpts();
    loaded.value = true;
  } catch (e) {
    error.value = errorMessage(e);
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <details
    class="stage-details"
    @toggle="
      (e) => {
        if ((e.target as HTMLDetailsElement).open && !loaded) load();
      }
    "
  >
    <summary>从个人摘录添加依据（跨资料）</summary>
    <p v-if="error" class="error-message" role="alert">{{ error }}</p>
    <button type="button" :disabled="busy" @click="load">刷新个人摘录</button>
    <p v-if="loaded && !items.length">
      尚无个人摘录。请先在资料阅读器选文保存。
    </p>
    <ol class="stage-evidence">
      <li v-for="item in items" :key="item.id">
        <RouterLink :to="evidenceReaderLink(item)"
          >{{ item.documentTitle || "来源资料" }} ·
          {{ item.locationLabel }}</RouterLink
        >
        <blockquote>{{ item.quoteText }}</blockquote>
        <button type="button" @click="emit('add', item)">加入本稿依据</button>
      </li>
    </ol>
  </details>
</template>
