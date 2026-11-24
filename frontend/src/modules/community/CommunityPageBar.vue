<script setup lang="ts">
import type { Page } from "../../shared/api/types";
withDefaults(defineProps<{ result: Page<unknown>; label?: string; disabled?: boolean }>(), { label: "列表分页" });
defineEmits<{ change: [page: number] }>();
</script>

<template>
  <nav v-if="result.total > 0" class="community-pagination" :aria-label="label">
    <button type="button" :disabled="disabled || result.page <= 1" @click="$emit('change', result.page - 1)">上一页</button>
    <span>第 {{ result.page }} 页，共 {{ result.total }} 项</span>
    <button type="button" :disabled="disabled || result.page * result.pageSize >= result.total"
      @click="$emit('change', result.page + 1)">下一页</button>
  </nav>
</template>
