<script setup lang="ts">
import { computed, nextTick, ref, useId, watch } from "vue";
import type { CommunityHeading } from "./reading";
import { renderMarkdown } from "./markdown";
import type { Reference, SourceFormat, TextPolicy } from "./types";
import "./community.css";

const props = defineProps<{
  body: string;
  sourceFormat: SourceFormat;
  references?: readonly Reference[];
  policy?: TextPolicy | null;
  outline?: boolean;
}>();
const emit = defineEmits<{ outline: [headings: CommunityHeading[]] }>();
const root = ref<HTMLElement>();
const prefix = 'community-heading-' + useId().replace(/[^a-zA-Z0-9-]/g, '-');
const html = computed(() => props.sourceFormat === "MARKDOWN" && props.policy
  ? renderMarkdown(props.body, props.policy.config, props.references ?? [])
  : null);
watch([html, () => props.outline, () => props.body], async (_value, _old, onCleanup) => {
  let cancelled = false;
  onCleanup(() => { cancelled = true; });
  await nextTick();
  if (cancelled) return;
  const headings: CommunityHeading[] = [];
  if (props.outline && html.value !== null && root.value) {
    for (const [index, node] of Array.from(root.value.querySelectorAll<HTMLElement>('h1,h2,h3,h4,h5,h6')).entries()) {
      const title = node.textContent?.trim();
      if (!title) continue;
      node.id = `${prefix}-${index + 1}`;
      headings.push({ id: node.id, title, level: Number(node.tagName.slice(1)) });
    }
  }
  emit('outline', headings);
}, { immediate: true, flush: 'post' });
</script>

<template>
  <div v-if="html === null" ref="root" class="community-prose community-literal">{{ body }}</div>
  <div v-else ref="root" class="community-prose" v-html="html"></div>
</template>
