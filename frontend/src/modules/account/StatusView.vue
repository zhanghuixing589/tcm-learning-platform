<script setup lang="ts">
defineProps<{ title: string; message: string }>();
</script>
<template>
  <section class="form-page">
    <h1>{{ title }}</h1>
    <p>{{ message }}</p>
    <RouterLink to="/" class="primary-link">回到学习门户</RouterLink>
  </section>
</template>
