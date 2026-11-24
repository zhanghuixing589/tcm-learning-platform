<script setup lang="ts">
import { shallowRef, watch } from "vue";
import CommunityMarkdown from "./CommunityMarkdown.vue";
import CommunityReferences from "./CommunityReferences.vue";
import type { Reference, SourceFormat, TextPolicy } from "./types";

const props = defineProps<{
  body: string; sourceFormat: SourceFormat; references?: Reference[]; policy: TextPolicy | null; context: string;
}>();
const snapshot = shallowRef<{ body: string; sourceFormat: SourceFormat; references: Reference[] } | null>(null);
watch(() => props.context, () => { snapshot.value = null; });
function preview() {
  snapshot.value = { body: props.body, sourceFormat: props.sourceFormat,
    references: (props.references ?? []).map((reference) => ({ ...reference })) };
}
</script>

<template>
  <section class="community-source-preview" aria-label="正文预览">
    <div class="community-actions">
      <button type="button" @click="preview">{{ snapshot ? "更新正文预览" : "预览正文" }}</button>
      <button v-if="snapshot" type="button" @click="snapshot = null">关闭预览</button>
    </div>
    <div v-if="snapshot" class="community-panel">
      <p class="muted">预览保留点击时的内容。</p>
      <CommunityMarkdown v-bind="snapshot" :policy="policy" />
      <CommunityReferences :references="snapshot.references" :policy="policy" />
    </div>
  </section>
</template>
