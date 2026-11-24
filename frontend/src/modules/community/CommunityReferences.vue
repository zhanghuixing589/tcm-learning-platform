<script setup lang="ts">
import { referenceAnchor, safeExternalUrl } from "./markdown";
import type { Reference, TextPolicy } from "./types";

defineProps<{ references: readonly Reference[]; policy?: TextPolicy | null }>();
</script>

<template>
  <section v-if="references.length" class="community-reference-list" aria-label="参考文献">
    <h2>参考文献</h2>
    <ol>
      <li v-for="reference in references" :id="referenceAnchor(reference.referenceKey)"
        :key="reference.referenceKey" :value="reference.displayNumber" tabindex="-1">
        <strong>{{ reference.title }}</strong>
        <p v-if="reference.authors || reference.sourceName || reference.publicationYear || reference.locationLabel" class="muted">
          {{ [reference.authors, reference.sourceName, reference.publicationYear, reference.locationLabel].filter(Boolean).join(" · ") }}
        </p>
        <p v-if="reference.doi">DOI {{ reference.doi }}</p>
        <a v-if="policy && policy.config.allowedSyntax.includes('link') && safeExternalUrl(reference.url, policy.config)"
          :href="safeExternalUrl(reference.url, policy.config)!" target="_blank"
          rel="nofollow ugc noopener noreferrer">打开文献链接</a>
        <span v-else-if="reference.url" class="muted">{{ reference.url }}</span>
      </li>
    </ol>
  </section>
</template>
