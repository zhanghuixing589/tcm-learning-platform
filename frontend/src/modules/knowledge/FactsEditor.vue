<script setup lang="ts">
import { ref } from "vue";
import { errorMessage } from "../../shared/api/client";
import { stageApi, type KnowledgeInput, type KnowledgeSummary } from "./api";
const props = defineProps<{ value: KnowledgeInput; disabled?: boolean }>();
const herbQuery = ref(""),
  targetQuery = ref(""),
  herbs = ref<KnowledgeSummary[]>([]),
  targets = ref<KnowledgeSummary[]>([]),
  error = ref(""),
  busy = ref(false);
async function search(kind: "herb" | "relation") {
  busy.value = true;
  error.value = "";
  try {
    const result = await stageApi.knowledge({
      q: kind === "herb" ? herbQuery.value : targetQuery.value,
      type: kind === "herb" ? "HERB" : undefined,
      pageSize: 100,
    });
    if (kind === "herb") herbs.value = result.items;
    else targets.value = result.items;
  } catch (e) {
    error.value = errorMessage(e);
  } finally {
    busy.value = false;
  }
}
function herbTarget(index: number) {
  const row = props.value.ingredients[index]!;
  const target = herbs.value.find((x) => x.id === row.herbResourceId);
  row.herbRevisionId = target?.revisionId || "";
  row.herbName = target?.canonicalName;
}
function relationTarget(index: number) {
  const row = props.value.relations[index]!;
  const target = targets.value.find((x) => x.id === row.targetResourceId);
  row.targetRevisionId = target?.revisionId || "";
  row.targetName = target?.canonicalName;
}
</script>
<template>
  <div>
    <p v-if="error" class="error-message" role="alert">{{ error }}</p>
    <section v-if="value.type === 'HERB' && value.herb">
      <h2>药材属性</h2>
      <div class="stage-grid">
        <label class="stage-field"
          >药性<input
            v-model="value.herb.nature"
            :disabled="disabled"
            placeholder="如：温" /></label
        ><label class="stage-field"
          >药味<input
            v-model="value.herb.flavor"
            :disabled="disabled"
            placeholder="如：辛、甘"
        /></label>
      </div>
      <label class="stage-field"
        >来源与产地<textarea
          v-model="value.herb.originNote"
          :disabled="disabled"
          rows="3"
        ></textarea></label
      ><label class="stage-field"
        >注意事项<textarea
          v-model="value.herb.precautions"
          :disabled="disabled"
          rows="3"
        ></textarea>
      </label>
    </section>
    <section v-if="value.type === 'FORMULA' && value.formula">
      <h2>方剂信息</h2>
      <label class="stage-field"
        >方剂出处<input
          v-model="value.formula.originText"
          required
          maxlength="500"
          :disabled="disabled" /></label
      ><label class="stage-field"
        >功效记载<textarea
          v-model="value.formula.functionSummary"
          :disabled="disabled"
          rows="3"
        ></textarea></label
      ><label class="stage-field"
        >主治记载<textarea
          v-model="value.formula.indicationSummary"
          :disabled="disabled"
          rows="3"
        ></textarea></label
      ><label class="stage-field"
        >方剂注意事项<textarea
          v-model="value.formula.precautions"
          :disabled="disabled"
          rows="3"
        ></textarea>
      </label>
      <h2>方剂组成</h2>
      <p class="stage-hint">
        组成只能选择已公开的药材，保存时固定药材版本。用量照录来源，不换算为用药建议。
      </p>
      <div v-if="!disabled" class="stage-actions">
        <label class="stage-field" style="flex: 1"
          >查找药材<input
            v-model="herbQuery"
            @keydown.enter.prevent="search('herb')" /></label
        ><el-button :loading="busy" @click="search('herb')">查找药材</el-button>
      </div>
      <div
        v-for="(row, index) in value.ingredients"
        :key="index"
        class="stage-row"
      >
        <div class="stage-row-header">
          <h3>组成 {{ index + 1 }}</h3>
          <el-button
            v-if="!disabled"
            @click="value.ingredients.splice(index, 1)"
            :aria-label="`移除组成 ${index + 1}`"
            >移除</el-button
          >
        </div>
        <div class="stage-grid">
          <label class="stage-field"
            >药材<select
              v-model="row.herbResourceId"
              :disabled="disabled"
              @change="herbTarget(index)"
            >
              <option value="">选择已公开药材</option>
              <option
                v-if="
                  row.herbResourceId &&
                  !herbs.some((x) => x.id === row.herbResourceId)
                "
                :value="row.herbResourceId"
              >
                {{ row.herbName || "已保存药材" }}
              </option>
              <option v-for="herb in herbs" :key="herb.id" :value="herb.id">
                {{ herb.canonicalName }} · 第 {{ herb.versionNo }} 版
              </option>
            </select></label
          ><label class="stage-field"
            >配伍角色<select v-model="row.role" :disabled="disabled">
              <option value="">请选择</option>
              <option
                v-for="(label, role) in {
                  JUN: '君',
                  CHEN: '臣',
                  ZUO: '佐',
                  SHI: '使',
                  UNSPECIFIED: '未指定',
                }"
                :key="role"
                :value="role"
              >
                {{ label }}
              </option>
            </select></label
          ><label class="stage-field"
            >用量数值<input
              v-model.number="row.doseValue"
              type="number"
              min="0.0001"
              step="any"
              :disabled="disabled" /></label
          ><label class="stage-field"
            >用量单位<input
              v-model="row.doseUnit"
              :disabled="disabled"
              placeholder="如：两、克"
          /></label>
        </div>
        <label class="stage-field"
          >原文用量<input
            v-model="row.doseText"
            :disabled="disabled"
            placeholder="保留原文中的用量描述" /></label
        ><label class="stage-field"
          >组成依据<select
            v-model.number="row.evidenceIndex"
            :disabled="disabled"
          >
            <option :value="-1">请选择本稿依据</option>
            <option v-for="(evidence, i) in value.evidence" :key="i" :value="i">
              依据 {{ i + 1 }} ·
              {{ evidence.documentTitle || evidence.quoteText.slice(0, 30) }}
            </option>
          </select></label
        >
      </div>
      <el-button
        v-if="!disabled"
        @click="
          value.ingredients.push({
            herbResourceId: '',
            herbRevisionId: '',
            role: '',
            evidenceIndex: -1,
          })
        "
        >添加组成</el-button
      >
    </section>
    <section>
      <h2>知识关系</h2>
      <p class="stage-hint">关联已公开知识，并指定本稿中的依据。</p>
      <div v-if="!disabled" class="stage-actions">
        <label class="stage-field" style="flex: 1"
          >查找关联知识<input
            v-model="targetQuery"
            @keydown.enter.prevent="search('relation')" /></label
        ><el-button :loading="busy" @click="search('relation')"
          >查找知识</el-button
        >
      </div>
      <div
        v-for="(row, index) in value.relations"
        :key="index"
        class="stage-row"
      >
        <div class="stage-row-header">
          <h3>关系 {{ index + 1 }}</h3>
          <el-button
            v-if="!disabled"
            @click="value.relations.splice(index, 1)"
            :aria-label="`移除关系 ${index + 1}`"
            >移除</el-button
          >
        </div>
        <div class="stage-grid">
          <label class="stage-field"
            >关联知识<select
              v-model="row.targetResourceId"
              :disabled="disabled"
              @change="relationTarget(index)"
            >
              <option value="">选择已公开知识</option>
              <option
                v-if="
                  row.targetResourceId &&
                  !targets.some((x) => x.id === row.targetResourceId)
                "
                :value="row.targetResourceId"
              >
                {{ row.targetName || "已保存关联知识" }}
              </option>
              <option
                v-for="target in targets"
                :key="target.id"
                :value="target.id"
              >
                {{ target.canonicalName }} · 第 {{ target.versionNo }} 版
              </option>
            </select></label
          ><label class="stage-field"
            >关系类型<select v-model="row.relationType" :disabled="disabled">
              <option
                v-for="(label, type) in {
                  HAS_EFFECT: '具有功效',
                  INDICATED_FOR: '主治记载',
                  ENTERS_MERIDIAN: '归经',
                  COMPATIBLE_WITH: '配伍关系',
                  RELATED_TO: '相关知识',
                }"
                :key="type"
                :value="type"
              >
                {{ label }}
              </option>
            </select></label
          >
        </div>
        <label class="stage-field"
          >关系说明<textarea
            v-model="row.explanation"
            :disabled="disabled"
            rows="2"
          ></textarea></label
        ><label class="stage-field"
          >关系依据<select
            v-model.number="row.evidenceIndex"
            :disabled="disabled"
          >
            <option :value="-1">请选择本稿依据</option>
            <option v-for="(evidence, i) in value.evidence" :key="i" :value="i">
              依据 {{ i + 1 }} ·
              {{ evidence.documentTitle || evidence.quoteText.slice(0, 30) }}
            </option>
          </select></label
        >
      </div>
      <el-button
        v-if="!disabled"
        @click="
          value.relations.push({
            targetResourceId: '',
            targetRevisionId: '',
            relationType: 'RELATED_TO',
            evidenceIndex: -1,
          })
        "
        >添加关系</el-button
      >
    </section>
  </div>
</template>
