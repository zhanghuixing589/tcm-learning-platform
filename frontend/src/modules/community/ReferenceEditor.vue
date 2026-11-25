<script setup lang="ts">
import { nextTick, ref, watch } from "vue";
import { newReference } from "./editor";
import type { Reference } from "./types";

const props = defineProps<{ readonly?: boolean; maxReferences: number; maxReferenceChars: number }>();
const references = defineModel<Reference[]>({ required: true });
const active = ref("");
let highWater = 0;
watch(references, (items) => {
  highWater = items.reduce((max, item) => Math.max(max, item.displayNumber), highWater);
}, { immediate: true });

function set(key: string, update: Partial<Reference>) {
  references.value = references.value.map((item) => item.referenceKey === key ? { ...item, ...update } : item);
}
async function add() {
  if (props.readonly || references.value.length >= props.maxReferences) return;
  const item = newReference(references.value, highWater);
  references.value = [...references.value, item];
  active.value = item.referenceKey;
  await nextTick();
  document.getElementById("reference-title-" + item.referenceKey)?.focus();
}
function remove(key: string) {
  const item = references.value.find(item => item.referenceKey === key);
  if (props.readonly || !item || !window.confirm(`确定删除文献 [${item.displayNumber}] 吗？正文中的 [${item.displayNumber}] 引用不会自动移除，请核对后再保存。其余文献编号保持不变。`)) return;
  references.value = references.value.filter((item) => item.referenceKey !== key);
  if (active.value === key) active.value = "";
}
const fields = [
  ["authors", "作者"], ["sourceName", "来源名称"], ["doi", "DOI"], ["url", "网页地址"], ["locationLabel", "页码或位置"],
] as const;
</script>

<template>
  <section aria-label="编辑参考文献" class="community-reference-editor">
    <div class="reference-heading"><h2>参考文献 <small>{{ references.length }} / {{ maxReferences }}</small></h2><button v-if="!readonly" class="reference-add" type="button" aria-label="添加参考文献" :disabled="references.length >= maxReferences" @click="add">＋ 添加参考文献</button></div>
    <p class="muted">
      已填写 {{ references.length }} / {{ maxReferences }} 条，文献字段合计上限 {{ maxReferenceChars }} 字符。
      Markdown 正文使用 [编号] 引用文献。删除条目前请核对正文中的对应引用。
    </p>
    <p v-if="!references.length" class="reference-empty muted">需要引证资料时，在这里添加文献，再用 [编号] 引用。</p>
    <ul v-else class="community-rows reference-editor-list">
      <li v-for="item in references" :key="item.referenceKey" class="community-row">
        <button type="button" class="reference-toggle" :aria-controls="'reference-fields-' + item.referenceKey" :aria-expanded="active === item.referenceKey"
          @click="active = active === item.referenceKey ? '' : item.referenceKey">
          <span class="reference-number">[{{ item.displayNumber }}]</span><span>{{ item.title || '填写文献信息' }}</span><span class="reference-chevron" aria-hidden="true">{{ active === item.referenceKey ? '−' : '＋' }}</span>
        </button>
        <fieldset v-if="active === item.referenceKey" :id="'reference-fields-' + item.referenceKey" :disabled="readonly">
          <legend>文献 {{ item.displayNumber }}</legend>
          <label class="community-field">标题
            <input :id="'reference-title-' + item.referenceKey" :value="item.title"
              @input="set(item.referenceKey, { title: ($event.target as HTMLInputElement).value })" />
          </label>
          <div class="community-form-grid">
            <label v-for="[field, label] in fields" :key="field" class="community-field">
              {{ label }}
              <input :value="item[field] ?? ''" @input="set(item.referenceKey, { [field]: ($event.target as HTMLInputElement).value || null })" />
            </label>
            <label class="community-field">出版年份
              <input type="number" min="1" max="9999" step="1" :value="item.publicationYear"
                @input="set(item.referenceKey, { publicationYear: Number.isFinite(($event.target as HTMLInputElement).valueAsNumber) ? ($event.target as HTMLInputElement).valueAsNumber : null })" />
            </label>
          </div>
          <button v-if="!readonly" type="button" @click="remove(item.referenceKey)">删除文献 {{ item.displayNumber }}</button>
        </fieldset>
      </li>
    </ul>
  </section>
</template>
