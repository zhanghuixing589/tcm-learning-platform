<script setup lang="ts">
import { ref, watch } from "vue";
import type { Reference } from "./types";

const props = defineProps<{ references: Reference[]; disabled: boolean }>();
const emit = defineEmits<{ reorder: [keys: string[]] }>();
const ordered = ref<Reference[]>([]);
watch(() => props.references, (items) => {
  ordered.value = [...items].sort((a, b) => a.displayNumber - b.displayNumber);
}, { immediate: true });
function move(index: number, step: number) {
  const current = ordered.value[index];
  ordered.value.splice(index, 1);
  ordered.value.splice(index + step, 0, current);
}
</script>

<template>
  <details v-if="references.length" class="community-details">
    <summary>重排已保存的文献</summary>
    <p class="muted">先保存草稿。确认后，系统会一并更新编号和正文中引用的编号。</p>
    <ol class="community-rows">
      <li v-for="(item, index) in ordered" :key="item.referenceKey" class="community-row community-actions">
        <span>[{{ item.displayNumber }}] {{ item.title }}</span>
        <button type="button" :disabled="disabled || index === 0" :aria-label="'上移 ' + item.title" @click="move(index, -1)">上移</button>
        <button type="button" :disabled="disabled || index === ordered.length - 1" :aria-label="'下移 ' + item.title" @click="move(index, 1)">下移</button>
      </li>
    </ol>
    <button type="button" :disabled="disabled" @click="emit('reorder', ordered.map((item) => item.referenceKey))">确认重排与更新引用</button>
  </details>
</template>
