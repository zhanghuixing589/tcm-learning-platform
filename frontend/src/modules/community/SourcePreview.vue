<script setup lang="ts">
import CommunityMarkdown from './CommunityMarkdown.vue';
import CommunityReferences from './CommunityReferences.vue';
import type { Reference, SourceFormat, TextPolicy } from './types';
defineProps<{body: string; sourceFormat: SourceFormat; references?: Reference[]; policy: TextPolicy | null; context: string}>();
</script>
<template>
 <section class="community-source-preview" aria-label="正文预览">
  <p v-if="sourceFormat === 'MARKDOWN' && !policy" class="muted" role="status">排版规则尚未加载，暂显示源文本。</p>
  <CommunityMarkdown :body="body" :source-format="sourceFormat" :references="references" :policy="policy" />
  <CommunityReferences :references="references || []" :policy="policy" />
 </section>
</template>
