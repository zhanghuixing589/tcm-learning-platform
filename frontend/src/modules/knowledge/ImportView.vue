<script setup lang="ts">
import { ref } from "vue";
import {
  post,
  type KnowledgeInput,
  type KnowledgeDetail,
  type Evidence,
  typeLabels,
} from "./api";
import { parseImportCsv, knowledgePayload, validateKnowledge } from "./editor";
import { useWorkspace } from "./useWorkspace";
import WorkspaceState from "./WorkspaceState.vue";
import EvidenceEditor from "./EvidenceEditor.vue";
import "./stage.css";
const { busy, actionError, message, act } = useWorkspace();
const csv = ref("类型,规范名称,标题,正文,别名\n"),
  rows = ref<KnowledgeInput[]>([]),
  evidence = ref<Evidence[]>([]),
  created = ref<KnowledgeDetail[]>([]);
function preview() {
  actionError.value = "";
  created.value = [];
  try {
    rows.value = parseImportCsv(csv.value);
  } catch (e) {
    rows.value = [];
    actionError.value = e instanceof Error ? e.message : "请检查 CSV 内容";
  }
}
async function choose(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0];
  if (!file) return;
  if (file.size > 2 * 1024 * 1024) {
    actionError.value = "CSV 文件请控制在 2MB 以内";
    return;
  }
  csv.value = await file.text();
  preview();
}
function submit() {
  const items = rows.value.map((row) =>
    knowledgePayload({
      ...row,
      evidence: evidence.value.map((x) => ({ ...x })),
    }),
  );
  const errors = items.flatMap((row, i) =>
    validateKnowledge(row).map((e) => `第 ${i + 1} 行：${e}`),
  );
  if (errors.length) {
    actionError.value = errors.slice(0, 8).join("；");
    return;
  }
  act(
    () => post<{ items: KnowledgeDetail[] }>("/knowledge/import", { items }),
    (value) => {
      created.value = value.items;
      rows.value = [];
    },
    "批量草稿已创建，请逐项核对后提交审核",
  );
}
</script>
<template>
  <section class="stage-page">
    <RouterLink class="back-link" to="/contribute?tab=knowledge"
      >返回贡献工作台</RouterLink
    >
    <h1>批量导入知识草稿</h1>
    <p class="muted">
      每次 1–50
      项。先预览并修改内容，再关联共同来源依据。导入只创建草稿，不会直接发布。
    </p>
    <WorkspaceState :action-error="actionError" :message="message"
      ><label class="stage-field"
        >上传 UTF-8 CSV 文件<input
          type="file"
          accept=".csv,text/csv"
          @change="choose" /></label
      ><label class="stage-field"
        >CSV 内容<textarea
          v-model="csv"
          aria-label="CSV 内容"
          rows="7"
          spellcheck="false"
        ></textarea
        ><small
          >列顺序：类型、规范名称、标题、正文、别名。类型使用 CONCEPT / HERB /
          FORMULA / EFFECT / SYMPTOM / MERIDIAN；多个别名用 |
          分隔。正文含逗号或换行时用英文双引号包裹。</small
        ></label
      ><el-button @click="preview">预览导入内容</el-button>
      <section v-if="rows.length">
        <h2>待导入 {{ rows.length }} 项</h2>
        <div v-for="(row, index) in rows" :key="index" class="stage-row">
          <div class="stage-row-header">
            <h3>草稿 {{ index + 1 }} · {{ typeLabels[row.type] }}</h3>
            <el-button @click="rows.splice(index, 1)">移除此项</el-button>
          </div>
          <div class="stage-grid">
            <label class="stage-field"
              >规范名称<input v-model="row.canonicalName" required /></label
            ><label class="stage-field"
              >标题<input v-model="row.title" required
            /></label>
          </div>
          <label class="stage-field"
            >正文<textarea v-model="row.body" rows="3"></textarea>
          </label>
          <label
            v-if="row.type === 'FORMULA' && row.formula"
            class="stage-field"
            >方剂出处<input
              v-model="row.formula.originText"
              required
              maxlength="500"
          /></label>
        </div>
        <EvidenceEditor
          v-model="evidence"
          @remove="evidence.splice($event, 1)"
        />
        <p class="stage-hint">
          以上依据会关联到本批次的全部草稿。药材属性、方剂组成和知识关系请在创建后逐项完善。
        </p>
        <el-button
          type="primary"
          :loading="busy"
          :disabled="!evidence.length"
          @click="submit"
          >创建 {{ rows.length }} 项知识草稿</el-button
        >
      </section>
      <ul v-if="created.length" class="stage-list">
        <li v-for="item in created" :key="item.id">
          <RouterLink :to="`/contribute/knowledge/${item.id}`"
            >编辑 {{ item.title }}</RouterLink
          >
        </li>
      </ul></WorkspaceState
    >
  </section>
</template>
