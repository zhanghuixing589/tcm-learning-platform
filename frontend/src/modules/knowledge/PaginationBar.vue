<script setup lang="ts">
defineProps<{ page: number; total: number; pageSize?: number }>();
defineEmits<{ change: [page: number] }>();
</script>
<template>
  <nav v-if="total > 0" class="stage-pagination" aria-label="列表分页">
    <el-button :disabled="page <= 1" @click="$emit('change', page - 1)"
      >上一页</el-button
    ><span>第 {{ page }} 页，共 {{ total }} 项</span
    ><el-button
      :disabled="page * (pageSize || 20) >= total"
      @click="$emit('change', page + 1)"
      >下一页</el-button
    >
  </nav>
</template>
