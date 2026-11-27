<script setup lang="ts">
import { computed } from "vue";
import {
  typeLabels,
  type Category,
  type KnowledgeTag,
  type KnowledgeInput,
} from "./api";
import { removeEvidence } from "./editor";
import EvidenceEditor from "./EvidenceEditor.vue";
import FactsEditor from "./FactsEditor.vue";
const props = defineProps<{
  categories: Category[];
  tags: KnowledgeTag[];
  editable: boolean;
  isNew: boolean;
  previousTags?: KnowledgeTag[];
}>();
const form = defineModel<KnowledgeInput>({ required: true });
const aliases = computed({
  get: () => form.value.aliases.join("\n"),
  set: (value: string) => (form.value.aliases = value.split(/[\n、,，]/)),
});
const allowedCategories = computed(() =>
  props.categories.filter((c) => c.type === form.value.type),
);
</script>
<template>
  <div class="knowledge-draft-form">
    <fieldset class="taxonomy-tags" :disabled="!editable">
      <legend>通用标签（可跨知识类型，最多20项）</legend>
      <p v-if="!tags.length" class="stage-hint">暂无可选标签，由管理员维护。</p>
      <label v-for="tag in tags" :key="tag.id"
        ><input v-model="form.tagIds" type="checkbox" :value="tag.id" />{{
          tag.name
        }}</label
      ><label
        v-for="tag in previousTags?.filter(
          (tag) => !tags.some((active) => active.id === tag.id),
        ) || []"
        :key="tag.id"
        ><input v-model="form.tagIds" type="checkbox" :value="tag.id" />{{
          tag.name
        }}（已停用，保存前取消）</label
      >
    </fieldset>
    <div class="stage-grid">
      <label class="stage-field"
        >知识类型<select
          v-model="form.type"
          @change="form.categoryId = undefined"
          aria-label="知识类型"
          :disabled="!isNew || !editable"
        >
          <option v-for="(label, type) in typeLabels" :key="type" :value="type">
            {{ label }}
          </option></select
        ><small>创建后知识类型固定，新版本沿用同一类型。</small></label
      ><label class="stage-field"
        >类型专属目录<select v-model="form.categoryId" :disabled="!editable">
          <option :value="undefined">未分类</option>
          <option
            v-if="
              form.categoryId &&
              !allowedCategories.some(
                (category) => category.id === form.categoryId,
              )
            "
            :value="form.categoryId"
            disabled
          >
            原目录已归档或停用，请重新选择
          </option>
          <option
            v-for="category in allowedCategories"
            :key="category.id"
            :value="category.id"
          >
            {{ category.name }}
          </option>
        </select></label
      ><label class="stage-field"
        >规范名称<input
          v-model="form.canonicalName"
          required
          maxlength="160"
          :disabled="!editable" /></label
      ><label class="stage-field"
        >知识标题<input
          v-model="form.title"
          required
          maxlength="255"
          :disabled="!editable"
      /></label>
    </div>
    <label class="stage-field"
      >别名<textarea
        v-model="aliases"
        rows="2"
        :disabled="!editable"
        placeholder="每行一个别名，也可用顿号分隔"
      ></textarea></label
    ><label class="stage-field"
      >知识正文<textarea
        v-model="form.body"
        rows="10"
        required
        :disabled="!editable"
        maxlength="100000"
      ></textarea></label
    ><label class="stage-field"
      >变更说明<textarea
        v-model="form.changeNote"
        :disabled="!editable"
        rows="2"
        maxlength="1000"
        placeholder="说明新增或修订了哪些内容"
      ></textarea></label
    ><EvidenceEditor
      v-model="form.evidence"
      :disabled="!editable"
      @remove="removeEvidence(form, $event)"
    /><FactsEditor :value="form" :disabled="!editable" />
  </div>
</template>
