<script setup lang="ts">
import type { Outline } from "./readerApi";
defineProps<{ items: Outline[] }>();
defineEmits<{ choose: [unitNo: number, localId?: string] }>();
</script>
<template>
  <ul class="reader-outline">
    <li v-for="(item, index) in items" :key="index">
      <button @click="$emit('choose', item.unitNo, item.localId)">
        {{ item.title }}</button
      ><ReaderOutline
        v-if="item.children?.length"
        :items="item.children"
        @choose="(n, l) => $emit('choose', n, l)"
      />
    </li>
  </ul>
</template>
