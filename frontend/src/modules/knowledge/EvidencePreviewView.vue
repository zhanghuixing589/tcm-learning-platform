<script setup lang="ts">
import { ref, watch } from "vue";
import { useRoute } from "vue-router";
import { readerApi } from "./readerApi";
import { evidenceReaderLink } from "./readerSelection";
import { errorMessage } from "../../shared/api/client";
import "./stage.css";
const route = useRoute(),
  item = ref<Awaited<ReturnType<typeof readerApi.evidencePreview>> | null>(
    null,
  ),
  error = ref("");
let epoch = 0;
async function load() {
  const ticket = ++epoch;
  item.value = null;
  error.value = "";
  try {
    const result = await readerApi.evidencePreview(
      String(route.params.id),
      String(route.params.revisionId),
      String(route.params.evidenceId),
    );
    if (ticket === epoch) item.value = result;
  } catch (e) {
    if (ticket === epoch) error.value = errorMessage(e);
  }
}
watch(() => route.fullPath, load, { immediate: true });
</script>
<template>
  <section class="stage-page">
    <RouterLink :to="`/knowledge/${route.params.id}`">返回知识</RouterLink>
    <h1>核对来源引文</h1>
    <p v-if="error" role="alert" class="error-message">{{ error }}</p>
    <template v-if="item"
      ><h2>{{ item.document.title }}</h2>
      <p>{{ item.evidence.locationLabel }}</p>
      <blockquote class="stage-prose">{{ item.evidence.quoteText }}</blockquote>
      <p v-if="item.restricted" class="stage-file-note">
        此处仅展示当前知识已获许可的引文，全文访问受限。
      </p>
      <p v-if="item.reason">{{ item.reason }}</p>
      <RouterLink
        v-if="item.canOpenReader"
        :to="evidenceReaderLink(item.evidence)"
        >打开固定阅读版本并定位原文</RouterLink
      >
      <p v-else>当前权限不允许打开全文或整页预览。</p></template
    >
    <p v-else-if="!error" role="status">正在核对引文访问范围…</p>
  </section>
</template>
