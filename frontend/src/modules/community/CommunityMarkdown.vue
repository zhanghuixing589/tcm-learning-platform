<script setup lang="ts">
import { computed } from "vue";
import { renderMarkdown } from "./markdown";
import type { Reference, SourceFormat, TextPolicy } from "./types";
import "./community.css";

const props = defineProps<{
  body: string;
  sourceFormat: SourceFormat;
  references?: readonly Reference[];
  policy?: TextPolicy | null;
}>();
const html = computed(() => props.sourceFormat === "MARKDOWN" && props.policy
  ? renderMarkdown(props.body, props.policy.config, props.references ?? [])
  : null);
</script>

<template>
  <div v-if="html === null" class="community-prose community-literal">{{ body }}</div>
  <div v-else class="community-prose" v-html="html"></div>
</template>
