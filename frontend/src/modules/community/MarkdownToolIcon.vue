<script setup lang="ts">
import type { MarkdownAction } from './markdownEditing';
defineProps<{ action: MarkdownAction }>();
const paths: Record<MarkdownAction,string> = {
 heading:'M5 5v14M19 5v14M5 12h14',
 bold:'M6 4h7a4 4 0 0 1 0 8H6m0-8v16h8a4 4 0 0 0 0-8H6',
 italic:'M10 4h9M5 20h9M15 4 9 20',
 ordered:'M10 6h10M10 12h10M10 18h10M3 4h1v4M2 11c3-2 4 1 1 3h3M2 17h3l-2 2h2',
 unordered:'M9 6h11M9 12h11M9 18h11M3 6h1M3 12h1M3 18h1',
 quote:'M4 7h6v6H5c0 3 1 4 3 4M14 7h6v6h-5c0 3 1 4 3 4',
 link:'m10 14 4-4M8 15l-1 1a3 3 0 0 1-4-4l4-4a3 3 0 0 1 4 0m2 1 1-1a3 3 0 0 1 4 4l-4 4a3 3 0 0 1-4 0',
 code:'m8 7-5 5 5 5m8-10 5 5-5 5M14 4l-4 16',
};
</script>
<template><svg viewBox="0 0 24 24" class="markdown-tool-icon" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path :d="paths[action]" /></svg></template>
