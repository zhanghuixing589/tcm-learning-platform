<script setup lang="ts">
defineProps<{ kind?: string }>();
</script>
<template>
  <span class="content-glyph" :class="'glyph-' + (kind || 'CONCEPT')" aria-hidden="true">
    <svg viewBox="0 0 64 64" fill="none" stroke="currentColor" stroke-width="1.5">
      <template v-if="kind === 'HERB'"><path d="M32 54V24M32 41C12 42 10 25 10 25s22-3 22 16ZM32 30C32 11 51 10 51 10s3 20-19 20Z"/><path d="m20 32 12 9 12-23"/></template>
      <template v-else-if="kind === 'FORMULA'"><path d="M10 30h44c0 14-9 20-22 20S10 44 10 30ZM21 54h22M22 21c-7-7 6-9 0-16M33 21c-7-7 6-9 0-16M44 21c-7-7 6-9 0-16"/></template>
      <template v-else-if="kind === 'POST'"><path d="M10 13h44v31H29L16 54V44h-6ZM19 24h26M19 33h18"/></template>
      <template v-else><path d="M10 12h17l5 4 5-4h17v39H37l-5 4-5-4H10ZM32 16v39M17 23h9M17 31h9M17 39h9M38 23h9M38 31h9M38 39h9"/></template>
    </svg>
  </span>
</template>
