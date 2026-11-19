<script setup lang="ts">
defineProps<{
  loading?: boolean;
  error?: string;
  actionError?: string;
  message?: string;
}>();
defineEmits<{ retry: [] }>();
</script>
<template>
  <el-skeleton v-if="loading" :rows="6" animated aria-label="正在加载" />
  <div v-else-if="error" class="stage-empty" role="alert">
    <h2>内容暂未加载</h2>
    <p>{{ error }}</p>
    <el-button @click="$emit('retry')">重新加载</el-button>
  </div>
  <template v-else
    ><p v-if="actionError" class="error-message" role="alert">
      {{ actionError }}
    </p>
    <p v-if="message" class="success-message" role="status">{{ message }}</p>
    <slot
  /></template>
</template>
