<script setup lang="ts">
import { ref, onMounted } from "vue";
import { stageApi, type KnowledgeDetail, type KnowledgeSummary } from "./api";
import { useWorkspace } from "./useWorkspace";
import WorkspaceState from "./WorkspaceState.vue";
import "./stage.css";
const { loading, busy, error, actionError, message, load, act } =
  useWorkspace();
const q = ref(""),
  options = ref<KnowledgeSummary[]>([]),
  ids = ref<string[]>([]),
  formulas = ref<KnowledgeDetail[]>([]);
function search() {
  load(
    () => stageApi.knowledge({ q: q.value, type: "FORMULA", pageSize: 100 }),
    (result) => (options.value = result.items),
  );
}
function compare() {
  act(
    () => stageApi.compare(ids.value),
    (result) => (formulas.value = result.formulas),
    "比较已更新",
  );
}
onMounted(search);
</script>
<template>
  <section class="stage-page">
    <RouterLink class="back-link" to="/knowledge">返回知识中心</RouterLink>
    <header>
      <h1>方剂比较</h1>
      <p class="muted">选择 2–3 个已公开方剂，对照组成、功效和注意事项。</p>
    </header>
    <form class="stage-toolbar" @submit.prevent="search">
      <label class="stage-field search-field"
        >查找方剂<input v-model="q" maxlength="100" /></label
      ><el-button native-type="submit">查找</el-button>
    </form>
    <WorkspaceState
      :loading="loading"
      :error="error"
      :action-error="actionError"
      :message="message"
      @retry="search"
      ><div v-if="options.length" class="stage-section">
        <label
          v-for="option in options"
          :key="option.id"
          class="stage-inline-label"
          ><input
            v-model="ids"
            type="checkbox"
            :value="option.id"
            :disabled="!ids.includes(option.id) && ids.length >= 3"
          /><span
            >{{ option.canonicalName }} · 第 {{ option.versionNo }} 版</span
          ></label
        >
      </div>
      <div v-else class="stage-empty">
        <h2>没有可选方剂</h2>
        <p>调整关键词，或等待方剂审核发布。</p>
      </div>
      <el-button
        type="primary"
        :disabled="ids.length < 2 || ids.length > 3"
        :loading="busy"
        @click="compare"
        >比较所选 {{ ids.length }} 个方剂</el-button
      >
      <div v-if="formulas.length" class="stage-table-wrap stage-section">
        <table class="stage-table">
          <thead>
            <tr>
              <th scope="col">比较项目</th>
              <th v-for="formula in formulas" :key="formula.id" scope="col">
                <RouterLink :to="`/knowledge/${formula.id}`">{{
                  formula.canonicalName
                }}</RouterLink>
              </th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <th scope="row">出处</th>
              <td v-for="formula in formulas" :key="formula.id">
                {{ formula.formula?.originText || "未填写" }}
              </td>
            </tr>
            <tr>
              <th scope="row">组成</th>
              <td v-for="formula in formulas" :key="formula.id">
                <p
                  v-for="ingredient in formula.ingredients"
                  :key="ingredient.id"
                >
                  {{ ingredient.herbName }}
                  {{
                    ingredient.doseText ||
                    [ingredient.doseValue, ingredient.doseUnit]
                      .filter(Boolean)
                      .join(" ")
                  }}
                </p>
              </td>
            </tr>
            <tr>
              <th scope="row">功效</th>
              <td v-for="formula in formulas" :key="formula.id">
                {{ formula.formula?.functionSummary || "未填写" }}
              </td>
            </tr>
            <tr>
              <th scope="row">主治记载</th>
              <td v-for="formula in formulas" :key="formula.id">
                {{ formula.formula?.indicationSummary || "未填写" }}
              </td>
            </tr>
            <tr>
              <th scope="row">注意事项</th>
              <td v-for="formula in formulas" :key="formula.id">
                {{ formula.formula?.precautions || "未填写" }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <p class="stage-hint">
        本比较用于资料学习，不用于诊断或选择个人用药。
      </p></WorkspaceState
    >
  </section>
</template>
