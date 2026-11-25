<script setup lang="ts">
import { computed } from "vue";
const props = defineProps<{ state: string; label: string }>();
const tone = computed(() => {
  if (["APPROVED", "PUBLISHED", "ACTIVE", "SUCCEEDED", "RESOLVED"].includes(props.state)) return "success";
  if (["REJECTED", "FAILED", "DEAD", "SUSPENDED"].includes(props.state)) return "danger";
  if (["PENDING", "IN_REVIEW", "CLAIMED", "RUNNING", "QUEUED", "RETRY_WAIT", "OPEN"].includes(props.state)) return "warning";
  return "neutral";
});
</script>
<template><span class="status-badge" :class="'status-' + tone">{{ label }}</span></template>
