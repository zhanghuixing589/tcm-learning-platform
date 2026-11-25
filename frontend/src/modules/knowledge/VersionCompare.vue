<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { errorMessage } from "../../shared/api/client";
import { stageApi, type KnowledgeDetail } from "./api";
const props = defineProps<{ resourceId: string }>();
const versions = ref<KnowledgeDetail[]>([]),
  left = ref<KnowledgeDetail | null>(null),
  right = ref<KnowledgeDetail | null>(null),
  leftId = ref(""),
  rightId = ref(""),
  error = ref(""),
  busy = ref(false),
  opened = ref(false);
let epoch = 0;
const fields = computed(() => {
  const a = left.value,
    b = right.value;
  if (!a || !b) return [];
  const facts = (item: KnowledgeDetail) =>
    item.herb
      ? `药性：${item.herb.nature}\n药味：${item.herb.flavor}\n来源：${item.herb.originNote}\n注意：${item.herb.precautions}`
      : item.formula
        ? `出处：${item.formula.originText}\n功效：${item.formula.functionSummary}\n主治：${item.formula.indicationSummary}\n注意：${item.formula.precautions}`
        : "无";
  const ingredients = (item: KnowledgeDetail) =>
    item.ingredients
      .map(
        (x) =>
          `${x.herbName || x.herbResourceId}（版本 ${x.herbRevisionId}） ${x.role} ${x.doseText || [x.doseValue, x.doseUnit].filter(Boolean).join(" ")} 依据${x.evidenceIndex + 1}`,
      )
      .join("\n");
  const evidence = (item: KnowledgeDetail) =>
    item.evidence
      .map(
        (x) =>
          `${x.documentTitle || x.documentId} ${x.locationLabel || ""}\n${x.quoteText}`,
      )
      .join("\n\n");
  const relations = (item: KnowledgeDetail) =>
    item.relations
      .map(
        (x) =>
          `${x.targetName || x.targetResourceId}（版本 ${x.targetRevisionId}） ${x.relationType} ${x.explanation || ""} 依据${x.evidenceIndex + 1}`,
      )
      .join("\n");
  return [
    { label: "规范名称", a: a.canonicalName, b: b.canonicalName },
    { label: "标题", a: a.title, b: b.title },
    {
      label: "分类",
      a: a.categoryName || "未分类",
      b: b.categoryName || "未分类",
    },
    { label: "别名", a: a.aliases.join("、"), b: b.aliases.join("、") },
    { label: "通用标签", a: a.tags?.map(tag=>tag.name).join("、") || "无", b: b.tags?.map(tag=>tag.name).join("、") || "无" },
    { label: "正文", a: a.body, b: b.body },
    { label: "属性", a: facts(a), b: facts(b) },
    { label: "组成", a: ingredients(a), b: ingredients(b) },
    { label: "关系", a: relations(a), b: relations(b) },
    { label: "依据", a: evidence(a), b: evidence(b) },
  ];
});
async function loadVersions() {
  if (!opened.value || versions.value.length) return;
  busy.value = true;
  error.value = "";
  const ticket = epoch;
  try {
    const result = await stageApi.versions(props.resourceId);
    if (ticket !== epoch) return;
    versions.value = result;
    leftId.value = result.at(-1)?.revisionId || "";
    rightId.value = result[0]?.revisionId || "";
    await compare();
  } catch (e) {
    if (ticket === epoch) error.value = errorMessage(e);
  } finally {
    if (ticket === epoch) busy.value = false;
  }
}
async function compare() {
  if (!leftId.value || !rightId.value) return;
  const ticket = epoch;
  busy.value = true;
  error.value = "";
  try {
    const [a, b] = await Promise.all([
      stageApi.version(props.resourceId, leftId.value),
      stageApi.version(props.resourceId, rightId.value),
    ]);
    if (ticket === epoch) {
      left.value = a;
      right.value = b;
    }
  } catch (e) {
    if (ticket === epoch) error.value = errorMessage(e);
  } finally {
    if (ticket === epoch) busy.value = false;
  }
}
function toggle(event: Event) {
  opened.value = (event.target as HTMLDetailsElement).open;
  loadVersions();
}
watch(
  () => props.resourceId,
  () => {
    epoch++;
    versions.value = [];
    left.value = right.value = null;
    if (opened.value) loadVersions();
  },
);
</script>
<template>
  <details class="stage-details stage-version-compare" @toggle="toggle">
    <summary>查看版本与变更</summary>
    <p v-if="error" class="error-message" role="alert">
      {{ error }}
      <el-button @click="versions.length ? compare() : loadVersions()"
        >重试</el-button
      >
    </p>
    <el-skeleton v-if="busy" :rows="3" animated /><template
      v-else-if="versions.length"
      ><div class="stage-grid">
        <label class="stage-field"
          >比较左侧版本<select v-model="leftId" @change="compare">
            <option
              v-for="version in versions"
              :key="version.revisionId"
              :value="version.revisionId"
            >
              第 {{ version.versionNo }} 版 · {{ version.revisionState }}
            </option>
          </select></label
        ><label class="stage-field"
          >比较右侧版本<select v-model="rightId" @change="compare">
            <option
              v-for="version in versions"
              :key="version.revisionId"
              :value="version.revisionId"
            >
              第 {{ version.versionNo }} 版 · {{ version.revisionState }}
            </option>
          </select></label
        >
      </div>
      <p class="stage-hint">
        浅绿色单元格表示内容不同。可见版本范围由当前账号权限决定。
      </p>
      <div class="stage-table-wrap">
        <table class="stage-table">
          <thead>
            <tr>
              <th scope="col">内容</th>
              <th scope="col">第 {{ left?.versionNo }} 版</th>
              <th scope="col">第 {{ right?.versionNo }} 版</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="field in fields" :key="field.label">
              <th scope="row">{{ field.label }}</th>
              <td>{{ field.a || "未填写" }}</td>
              <td :class="{ changed: field.a !== field.b }">
                {{ field.b || "未填写" }}
              </td>
            </tr>
          </tbody>
        </table>
      </div></template
    >
    <p v-else>尚无可比较的版本。</p>
  </details>
</template>
