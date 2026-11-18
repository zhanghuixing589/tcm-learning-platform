<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from "vue";
import { api } from "../../shared/api/client";
const props = defineProps<{
  nickname: string;
  url?: string | null;
  large?: boolean;
}>();
const failed = ref(false);
const source = ref("");
let version = 0;
function release() {
  if (source.value.startsWith("blob:")) URL.revokeObjectURL(source.value);
  source.value = "";
}
watch(
  () => props.url,
  async (url) => {
    const ticket = ++version;
    failed.value = false;
    release();
    if (!url) return;
    if (!url.startsWith("/api/v1/users/")) {
      failed.value = true;
      return;
    }
    try {
      const response = await api.http.get<Blob>(url.slice("/api/v1".length), {
        responseType: "blob",
      });
      if (ticket === version) source.value = URL.createObjectURL(response.data);
    } catch {
      if (ticket === version) failed.value = true;
    }
  },
  { immediate: true },
);
onBeforeUnmount(() => {
  version++;
  release();
});
</script>
<template>
  <span
    class="user-avatar"
    :class="{ 'user-avatar-large': large }"
    aria-hidden="true"
    ><img
      v-if="source && !failed"
      :src="source"
      alt=""
      @error="failed = true"
    /><span v-else>{{ Array.from(nickname || "学")[0] }}</span></span
  >
</template>
