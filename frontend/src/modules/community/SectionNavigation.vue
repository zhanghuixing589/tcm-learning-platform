<script setup lang="ts">
import { computed, ref } from "vue";
import type { SectionGroup } from "./api";

const props = defineProps<{ groups: SectionGroup[] }>();
const joinedOnly = ref(false);
const groupsShown = computed(() => joinedOnly.value
  ? props.groups.map(({ section, children }) => ({ section, children: children.filter((child) => child.joined) }))
    .filter(({ section, children }) => section.joined || children.length)
  : props.groups);
</script>

<template>
  <aside class="community-panel community-sidebar">
    <h2>社区分区</h2>
    <label class="community-check"><input v-model="joinedOnly" type="checkbox" /> 我的分区</label>
    <nav class="community-nav" aria-label="社区分区">
      <RouterLink to="/community">全部内容</RouterLink>
      <div v-for="{ section, children } in groupsShown" :key="section.id">
        <RouterLink :to="'/community/sections/' + section.id">
          {{ section.name }}<span v-if="!section.writable" class="muted">只读</span>
        </RouterLink>
        <ul v-if="children.length">
          <li v-for="child in children" :key="child.id">
            <RouterLink :to="'/community/sections/' + child.id">
              {{ child.name }}<span v-if="!child.writable" class="muted">只读</span>
            </RouterLink>
          </li>
        </ul>
      </div>
      <p v-if="!groupsShown.length" class="community-empty">{{ joinedOnly ? "尚未加入分区。" : "暂无分区。" }}</p>
    </nav>
  </aside>
</template>
