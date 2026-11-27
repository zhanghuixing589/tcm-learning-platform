<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { onBeforeRouteLeave, useRoute, useRouter } from "vue-router";
import {
  stageApi,
  stateLabels,
  type Category,
  type KnowledgeTag,
  type KnowledgeDetail,
  type KnowledgeInput,
} from "./api";
import {
  blankKnowledge,
  knowledgePayload,
  validateKnowledge,
} from "./editor";
import { useWorkspace } from "./useWorkspace";
import WorkspaceState from "./WorkspaceState.vue";
import KnowledgeDraftForm from "./KnowledgeDraftForm.vue";
import VersionCompare from "./VersionCompare.vue";
import "./stage.css";
const route = useRoute(),
  router = useRouter(),
  { loading, busy, error, actionError, message, load, act } = useWorkspace();
const item = ref<KnowledgeDetail | null>(null),
  form = ref<KnowledgeInput>(blankKnowledge()),
  categories = ref<Category[]>([]),
  tags = ref<KnowledgeTag[]>([]),
  snapshot = ref(""),
  validation = ref<string[]>([]);
const isNew = computed(() => route.params.id === "new");
const editable = computed(
  () => isNew.value || item.value?.revisionState === "DRAFT",
);
const current = () => JSON.stringify(form.value);
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
    tagIds: value.tags?.map((tag) => tag.id) || [],
    aliases: [...value.aliases],
    evidence: value.evidence.map((x) => ({ ...x })),
    ingredients: value.ingredients.map((x) => ({ ...x })),
    relations: value.relations.map((x) => ({ ...x })),
  };
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
      tags: await stageApi.tags(),
      draft: isNew.value ? null : await stageApi.draft(String(route.params.id)),
    }),
    (value) => {
      categories.value = value.categories;
      tags.value = value.tags;
      if (value.draft) apply(value.draft);
      else {
        form.value = blankKnowledge();
        snapshot.value = current();
      }
    },
  );
}
function payload() {
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
      stageApi.saveDraft(isNew.value ? null : String(route.params.id), {
        ...value,
        expectedVersion: item.value?.lockVersion,
      }),
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
      <p v-if="item?.categoryLegacy" class="stage-file-note">
        原目录为历史归档目录。原正式版本保持不变，本次保存前请选择所属类型的新目录或未分类。
      </p>
      <form @submit.prevent="save">
        <KnowledgeDraftForm
          v-model="form"
          :categories="categories"
          :tags="tags"
          :editable="editable"
          :is-new="isNew"
          :previous-tags="item?.tags"
        />
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
