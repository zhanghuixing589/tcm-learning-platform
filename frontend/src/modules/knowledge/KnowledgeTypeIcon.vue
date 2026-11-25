<script setup lang="ts">
defineProps<{kind?:string}>();
</script>
<template><svg class="knowledge-type-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
 <path v-if="kind==='HERB'" d="M12 21V10m0 7C5 17 4 11 4 11s8-1 8 6Zm0-5C12 5 20 4 20 4s1 8-8 8Z"/>
 <path v-else-if="kind==='FORMULA'" d="M3 11h18c0 6-4 9-9 9s-9-3-9-9Zm5 11h8M8 7c-3-3 3-3 0-6m8 6c-3-3 3-3 0-6"/>
 <path v-else-if="kind==='EFFECT'" d="M5 20 17 8M5 17C1 8 10 4 21 3c0 10-3 18-13 15"/>
 <path v-else-if="kind==='SYMPTOM'" d="M9 4H5v18h14V4h-4M9 2h6v5H9ZM8 12h1m3 0h4m-8 5h1m3 0h4"/>
 <path v-else-if="kind==='MERIDIAN'" d="M12 7v5m0 0-6 5m6-5 6 5M12 7a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5ZM6 22a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5Zm12 0a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5Z"/>
 <path v-else-if="kind==='CONCEPT'" d="M3 4h6l3 2 3-2h6v16h-6l-3 2-3-2H3Zm9 2v16"/>
 <path v-else d="M3 4h4v17H3Zm7 0h4v17h-4Zm7 1 4-1 3 16-4 1Z"/>
</svg></template>
