<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { onBeforeRouteLeave, useRoute, useRouter } from "vue-router";
import {
  stageApi,
  typeLabels,
  stateLabels,
  type Category,
  type KnowledgeDetail,
  type KnowledgeInput,
} from "./api";
import {
  blankKnowledge,
  knowledgePayload,
  removeEvidence,
  validateKnowledge,
} from "./editor";
import { useWorkspace } from "./useWorkspace";
import WorkspaceState from "./WorkspaceState.vue";
import EvidenceEditor from "./EvidenceEditor.vue";
import FactsEditor from "./FactsEditor.vue";
import VersionCompare from "./VersionCompare.vue";
import "./stage.css";
const route = useRoute(),
  router = useRouter(),
  { loading, busy, error, actionError, message, load, act } = useWorkspace();
const item = ref<KnowledgeDetail | null>(null),
  form = ref<KnowledgeInput>(blankKnowledge()),
  categories = ref<Category[]>([]),
  aliases = ref(""),
  snapshot = ref(""),
  validation = ref<string[]>([]);
const isNew = computed(() => route.params.id === "new");
const editable = computed(
  () => isNew.value || item.value?.revisionState === "DRAFT",
);
const current = () =>
  JSON.stringify({ form: form.value, aliases: aliases.value });
const dirty = computed(
  () => editable.value && snapshot.value !== "" && current() !== snapshot.value,
);
function apply(value: KnowledgeDetail) {
  item.value = value;
  form.value = {
    ...blankKnowledge(value.type),
    ...value,
    herb: value.herb || blankKnowledge(value.type).herb,
    formula: value.formula || blankKnowledge(value.type).formula,
    categoryId: value.categoryId || undefined,
    aliases: [...value.aliases],
    evidence: value.evidence.map((x) => ({ ...x })),
    ingredients: value.ingredients.map((x) => ({ ...x })),
    relations: value.relations.map((x) => ({ ...x })),
  };
  aliases.value = value.aliases.join("\n");
  snapshot.value = current();
  validation.value = [];
}
function refresh() {
  validation.value = [];
  snapshot.value = "";
  item.value = null;
  load(
    async () => ({
      categories: await stageApi.categories(),
      draft: isNew.value ? null : await stageApi.draft(String(route.params.id)),
    }),
    (value) => {
      categories.value = value.categories;
      if (value.draft) apply(value.draft);
      else {
        form.value = blankKnowledge();
        aliases.value = "";
        snapshot.value = current();
      }
    },
  );
}
function payload() {
  form.value.aliases = aliases.value.split(/[\n、,，]/);
  return knowledgePayload(form.value);
}
function save() {
  const value = payload();
  validation.value = validateKnowledge(value);
  if (validation.value.length) {
    document.getElementById("knowledge-validation")?.focus();
    return;
  }
  act(
    () =>
      stageApi.saveDraft(isNew.value ? null : String(route.params.id), value),
    (result) => {
      apply(result);
      if (isNew.value) router.replace(`/contribute/knowledge/${result.id}`);
    },
    "知识草稿已保存",
  );
}
function submit() {
  if (!item.value) return;
  if (dirty.value) {
    actionError.value = "请先保存当前修改，再提交审核";
    return;
  }
  act(
    () => stageApi.revisionAction(item.value!.revisionId, "submit"),
    apply,
    "知识已提交审核，当前版本已冻结",
  );
}
function withdraw() {
  if (!item.value) return;
  act(
    () => stageApi.revisionAction(item.value!.revisionId, "withdraw"),
    apply,
    "已撤回审核。需要修改时请创建新稿",
  );
}
function revise() {
  if (!item.value) return;
  act(() => stageApi.revise(item.value!.id), apply, "已复制为新版本草稿");
}
watch(() => route.params.id, refresh, { immediate: true });
onBeforeRouteLeave(
  () => !dirty.value || window.confirm("当前草稿有未保存的修改，确定离开吗？"),
);
</script>
<template>
  <section class="stage-page">
    <RouterLink class="back-link" to="/contribute?tab=knowledge"
      >返回贡献工作台</RouterLink
    ><WorkspaceState
      :loading="loading"
      :error="error"
      :action-error="actionError"
      :message="message"
      @retry="refresh"
      ><header class="stage-heading">
        <div>
          <h1>{{ isNew ? "新建知识草稿" : item?.title }}</h1>
          <p v-if="item">
            第 {{ item.versionNo }} 版 · {{ stateLabels[item.revisionState]
            }}<span v-if="dirty"> · 有未保存修改</span>
          </p>
          <p v-else>按来源原文整理知识，提交后由专业审核者核对。</p>
        </div>
        <RouterLink
          v-if="item?.visibilityStatus === 'PUBLISHED'"
          :to="`/knowledge/${item.id}`"
          >查看正式版本</RouterLink
        >
      </header>
      <p v-if="item?.reviewNote" class="stage-file-note">
        审核意见：{{ item.reviewNote }}
      </p>
      <div
        v-if="validation.length"
        id="knowledge-validation"
        tabindex="-1"
        class="error-message"
        role="alert"
      >
        <p>请检查以下内容：</p>
        <ul>
          <li v-for="text in validation" :key="text">{{ text }}</li>
        </ul>
      </div>
      <form @submit.prevent="save">
        <div class="stage-grid">
          <label class="stage-field"
            >知识类型<select
              v-model="form.type"
              aria-label="知识类型"
              :disabled="!isNew || !editable"
            >
              <option
                v-for="(label, type) in typeLabels"
                :key="type"
                :value="type"
              >
                {{ label }}
              </option></select
            ><small>创建后知识类型固定，新版本沿用同一类型。</small></label
          ><label class="stage-field"
            >知识分类<select v-model="form.categoryId" :disabled="!editable">
              <option :value="undefined">未分类</option>
              <option
                v-for="category in categories"
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
        <div class="stage-actions stage-sticky-actions">
          <el-button
            v-if="editable"
            type="primary"
            native-type="submit"
            :loading="busy"
            >保存知识草稿</el-button
          ><el-button
            v-if="item && editable"
            :disabled="dirty"
            :loading="busy"
            @click="submit"
            >提交知识审核</el-button
          ><el-button
            v-if="item?.revisionState === 'IN_REVIEW'"
            :loading="busy"
            @click="withdraw"
            >撤回知识审核</el-button
          ><el-button
            v-if="item && !editable"
            type="primary"
            :loading="busy"
            @click="revise"
            >创建知识新稿</el-button
          ><span v-if="item && !editable" class="stage-hint"
            >本版本已冻结，复制新稿后可以修改。</span
          >
        </div>
      </form>
      <VersionCompare v-if="item" :resource-id="item.id"
    /></WorkspaceState>
  </section>
</template>
