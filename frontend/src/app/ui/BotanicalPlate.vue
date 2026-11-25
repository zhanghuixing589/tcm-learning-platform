<script setup lang="ts">
import { ref } from "vue";
const failed = ref(false);
</script>
<template>
  <figure class="botanical-plate">
    <div class="plate-heading"><span>自然 · 本草</span><span aria-hidden="true">HERBAL STUDIES</span></div>
    <img v-if="!failed" src="/images/botanical-chrysanthemum.webp" width="1024" height="1536"
      alt="白色菊花与绿色枝叶的植物风格插画" fetchpriority="high" @error="failed = true" />
    <div v-else class="plate-fallback"><span>本草</span><p>从自然中，认识知识。</p></div>
    <figcaption><span>一草一木，皆有可读之处。</span><small>AI 植物意象插画 · 非鉴别图谱</small></figcaption>
  </figure>
</template>
