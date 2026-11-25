<script setup lang="ts">
defineProps<{kind:'like'|'comment'|'bookmark'}>();
</script>
<template><svg class="community-glyph" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true"><path v-if="kind==='like'" d="M7 10v11H3V10h4Zm0 1 5-8c2 0 2 2 1 6h6c2 0 2 1 2 2l-2 8c0 1-1 2-2 2H7"/><path v-else-if="kind==='comment'" d="M4 4h16v12H9l-5 4V4Z"/><path v-else d="M6 3h12v18l-6-4-6 4V3Z"/></svg></template>
