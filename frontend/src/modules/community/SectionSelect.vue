<script setup lang="ts">
import type { SectionGroup } from "./api";
import type { Section } from "./types";

defineProps<{
  groups: SectionGroup[];
  eligible: (section: Section) => boolean;
  emptyLabel: string;
  id?: string;
  disabled?: boolean;
  required?: boolean;
  emptyDisabled?: boolean;
  current?: { id: string; name: string } | null;
}>();
const model = defineModel<string>({ required: true });
</script>

<template>
  <select :id="id" v-model="model" :disabled="disabled" :required="required">
    <option value="" :disabled="emptyDisabled">{{ emptyLabel }}</option>
    <option v-if="current" :value="current.id">{{ current.name }}</option>
    <optgroup v-for="{ section, children } in groups" :key="section.id" :label="section.name">
      <option v-if="eligible(section) && section.id !== current?.id" :value="section.id">{{ section.name }}</option>
      <option v-for="child in children.filter(eligible).filter((child) => child.id !== current?.id)" :key="child.id" :value="child.id">
        {{ child.name }}
      </option>
    </optgroup>
  </select>
</template>
