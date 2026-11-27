<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { onBeforeRouteLeave, useRoute, useRouter } from "vue-router";
import {
  stageApi,
  stateLabels,
  type KnowledgeInput,
  type Category,
  type KnowledgeTag,
} from "./api";
import { readerApi, type KnowledgeProposal } from "./readerApi";
import { blankKnowledge, knowledgePayload, validateKnowledge } from "./editor";
import { errorMessage } from "../../shared/api/client";
import KnowledgeDraftForm from "./KnowledgeDraftForm.vue";
import "./stage.css";
const route = useRoute(),
  router = useRouter(),
  item = ref<KnowledgeProposal | null>(null),
  form = ref<KnowledgeInput>(blankKnowledge()),
  categories = ref<Category[]>([]),
  tags = ref<KnowledgeTag[]>([]),
  error = ref(""),
  message = ref(""),
  busy = ref(false),
  loading = ref(false),
  validation = ref<string[]>([]),
  snapshot = ref("");
let epoch = 0,
  baseResourceId = "",
  baseRevisionId = "";
const isNew = computed(() => route.params.id === "new"),
  editable = computed(() => isNew.value || item.value?.status === "DRAFT"),
  dirty = computed(
    () =>
      editable.value &&
      snapshot.value !== "" &&
      JSON.stringify(form.value) !== snapshot.value,
  );
function apply(value: KnowledgeProposal) {
  item.value = value;
  form.value = {
    ...blankKnowledge(value.knowledge.type),
    ...JSON.parse(JSON.stringify(value.knowledge)),
  };
  snapshot.value = JSON.stringify(form.value);
}
async function load() {
  const ticket = ++epoch;
  busy.value = false;
  loading.value = true;
  error.value = "";
  item.value = null;
  try {
    const [cs, ts] = await Promise.all([
      stageApi.categories(),
      stageApi.tags(),
    ]);
    if (ticket !== epoch) return;
    categories.value = cs;
    tags.value = ts;
    if (isNew.value) {
      const source = await stageApi.detail(
        String(route.query.resourceId || ""),
      );
      if (ticket !== epoch) return;
      baseResourceId = source.id;
      baseRevisionId = source.revisionId;
      form.value = {
        ...knowledgePayload({
          ...source,
          categoryId: source.categoryId || undefined,
        }),
        tagIds: source.tags?.map((t) => t.id) || [],
      };
      snapshot.value = JSON.stringify(form.value);
    } else {
      const value = await readerApi.proposal(String(route.params.id));
      if (ticket === epoch) apply(value);
    }
  } catch (e) {
    if (ticket === epoch) error.value = errorMessage(e);
  } finally {
    if (ticket === epoch) loading.value = false;
  }
}
async function save() {
  const ticket=epoch;
  validation.value = validateKnowledge(form.value);
  if (validation.value.length) return;
  busy.value = true;
  error.value = "";
  try {
    let result: KnowledgeProposal;
    if (item.value)
      result = await readerApi.saveProposal(
        item.value.id,
        item.value.lockVersion,
        knowledgePayload(form.value),
      );
    else {
      result = await readerApi.createProposal(
        baseResourceId,
        baseRevisionId,
        knowledgePayload(form.value),
      );
    }
    if(ticket!==epoch)return;
    apply(result);
    message.value = "修订建议草稿已保存";
    if (isNew.value) await router.replace(`/contribute/proposals/${result.id}`);
  } catch (e) {
    if(ticket===epoch)error.value = errorMessage(e);
  } finally {
    if(ticket===epoch)busy.value = false;
  }
}
async function action(name: "submit" | "withdraw") {
  const ticket=epoch;
  if (!item.value) return;
  if (dirty.value) {
    error.value = "请先保存当前修改";
    return;
  }
  busy.value = true;
  error.value = "";
  try {
    const result=await readerApi.proposalAction(item.value.id, name);
    if(ticket!==epoch)return;
    apply(result);
    message.value = name === "submit" ? "修订建议已提交审核" : "修订建议已撤回";
  } catch (e) {
    if(ticket===epoch)error.value = errorMessage(e);
  } finally {
    if(ticket===epoch)busy.value = false;
  }
}
watch(() => route.fullPath, load, { immediate: true });
onBeforeUnmount(()=>{epoch++});
onBeforeRouteLeave(
  () => !dirty.value || window.confirm("修订建议有未保存修改，确定离开吗？"),
);
</script>
<template>
  <section class="stage-page source-workflow">
    <RouterLink
      :to="
        item
          ? `/knowledge/${item.resourceId}`
          : `/knowledge/${route.query.resourceId}`
      "
      >返回原知识</RouterLink
    >
    <h1>{{ isNew ? "提出独立修订建议" : "知识修订建议" }}</h1>
    <p>建议单独保存，不会改动原作者的草稿；审核通过后才更新正式版本。</p>
    <p v-if="item">
      提案 {{ item.id }} · {{ stateLabels[item.status] || item.status }} ·
      基准正式版本 {{ item.baseRevisionId }}
    </p>
    <p v-if="error" class="error-message" role="alert">{{ error }}</p>
    <p v-if="message" role="status">{{ message }}</p>
    <p v-if="loading" role="status">正在加载修订建议…</p>
    <template v-else
      ><p v-if="validation.length" role="alert" class="error-message">
        {{ validation.join("；") }}
      </p>
      <form @submit.prevent="save">
        <KnowledgeDraftForm
          v-model="form"
          :categories="categories"
          :tags="tags"
          :editable="editable"
          :is-new="false"
        />
        <div class="stage-actions">
          <button v-if="editable" type="submit" :disabled="busy">
            保存修订建议草稿</button
          ><button
            v-if="item && editable"
            type="button"
            :disabled="busy || dirty"
            @click="action('submit')"
          >
            提交修订建议审核</button
          ><button
            v-if="item?.status === 'IN_REVIEW'"
            type="button"
            :disabled="busy"
            @click="action('withdraw')"
          >
            撤回修订建议
          </button>
        </div>
      </form></template
    >
  </section>
</template>
