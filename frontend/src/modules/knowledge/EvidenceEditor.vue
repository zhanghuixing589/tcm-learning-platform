<script setup lang="ts">
import { onMounted, ref } from "vue";
import { errorMessage } from "../../shared/api/client";
import {
  stageApi,
  type Evidence,
  type Segment,
  type SourceDocument,
} from "./api";
const props = defineProps<{ modelValue: Evidence[]; disabled?: boolean }>();
const emit = defineEmits<{
  "update:modelValue": [value: Evidence[]];
  remove: [index: number];
}>();
const sources = ref<SourceDocument[]>([]),
  q = ref(""),
  documentId = ref(""),
  segments = ref<Segment[]>([]),
  segmentId = ref(""),
  quoteText = ref(""),
  locationLabel = ref(""),
  loading = ref(false),
  error = ref(""),
  total = ref(0),
  page = ref(1),
  segmentTotal = ref(0),
  segmentPage = ref(1);
let ticket = 0;
async function search(next = 1) {
  loading.value = true;
  error.value = "";
  try {
    const result = await stageApi.sources({
      approved: true,
      q: q.value,
      page: next,
      pageSize: 20,
    });
    sources.value = result.items;
    total.value = result.total;
    page.value = next;
  } catch (e) {
    error.value = errorMessage(e);
  } finally {
    loading.value = false;
  }
}
async function chooseDocument() {
  const current = ++ticket;
  segments.value = [];
  segmentId.value = "";
  quoteText.value = "";
  locationLabel.value = "";
  error.value = "";
  segmentTotal.value = 0;
  if (!documentId.value) return;
  try {
    const result = await stageApi.segments(documentId.value, { pageSize: 20 });
    if (current === ticket) {
      segments.value = result.items;
      segmentTotal.value = result.total;
      segmentPage.value = 1;
    }
  } catch (e) {
    if (current === ticket) error.value = errorMessage(e);
  }
}
async function segmentPagination(next: number) {
  const current = ++ticket;
  error.value = "";
  try {
    const result = await stageApi.segments(documentId.value, {
      page: next,
      pageSize: 20,
    });
    if (current === ticket) {
      segments.value = result.items;
      segmentTotal.value = result.total;
      segmentPage.value = next;
      segmentId.value = "";
    }
  } catch (e) {
    if (current === ticket) error.value = errorMessage(e);
  }
}
function chooseSegment() {
  const item = segments.value.find((x) => x.id === segmentId.value);
  if (item) {
    quoteText.value = item.body;
    locationLabel.value = [item.pageLabel, item.headingPath]
      .filter(Boolean)
      .join(" / ");
  }
}
function add() {
  if (!documentId.value || !quoteText.value.trim()) {
    error.value = "请选择已审核来源，并填写引文";
    return;
  }
  if (!segmentId.value && !locationLabel.value.trim()) {
    error.value = "手工引文需要填写位置说明，便于审核者核对原文";
    return;
  }
  emit("update:modelValue", [
    ...props.modelValue,
    {
      documentId: documentId.value,
      segmentId: segmentId.value || undefined,
      quoteText: quoteText.value.trim(),
      locationLabel: locationLabel.value,
      documentTitle: sources.value.find((x) => x.id === documentId.value)
        ?.title,
    },
  ]);
  quoteText.value = "";
  segmentId.value = "";
  locationLabel.value = "";
  error.value = "";
}
onMounted(() => search());
</script>
<template>
  <section>
    <h2>来源依据</h2>
    <p class="stage-hint">
      选择已审核且有效的来源。引文、位置和关联片段随本稿保存，提交后冻结。
    </p>
    <ol class="stage-evidence">
      <li v-for="(item, index) in modelValue" :key="index">
        <div class="stage-row-header">
          <RouterLink :to="`/sources/${item.documentId}`">{{
            item.documentTitle || `来源 ${item.documentId}`
          }}</RouterLink><el-button
            v-if="!disabled"
            @click="$emit('remove', index)"
            :aria-label="`移除依据 ${index + 1}`"
            >移除</el-button
          >
        </div>
        <p v-if="item.locationLabel" class="muted">{{ item.locationLabel }}</p>
        <blockquote>{{ item.quoteText }}</blockquote>
      </li>
    </ol>
    <p v-if="!modelValue.length" class="stage-hint">
      尚未添加依据。请先在下方选择来源。
    </p>
    <div v-if="!disabled" class="stage-section">
      <div class="stage-actions">
        <label class="stage-field" style="flex: 1"
          >搜索来源<input
            v-model="q"
            @keydown.enter.prevent="search()"
            placeholder="输入标题" /></label
        ><el-button :loading="loading" @click="search()">查找来源</el-button>
      </div>
      <p v-if="error" class="error-message" role="alert">{{ error }}</p>
      <label class="stage-field"
        >来源资料<select v-model="documentId" @change="chooseDocument">
          <option value="">请选择已审核资料</option>
          <option v-for="source in sources" :key="source.id" :value="source.id">
            {{ source.title }}
          </option>
        </select></label
      >
      <div v-if="total > 20" class="stage-actions">
        <el-button :disabled="page <= 1 || loading" @click="search(page - 1)"
          >上一页来源</el-button
        ><span>第 {{ page }} 页</span
        ><el-button
          :disabled="page * 20 >= total || loading"
          @click="search(page + 1)"
          >下一页来源</el-button
        >
      </div>
      <label v-if="documentId" class="stage-field"
        >原文片段（可选）<select
          v-model="segmentId"
          aria-label="原文片段（可选）"
          @change="chooseSegment"
        >
          <option value="">手工填写引文</option>
          <option
            v-for="segment in segments"
            :key="segment.id"
            :value="segment.id"
          >
            {{ segment.segmentNo }}. {{ segment.pageLabel }}
            {{ segment.body.slice(0, 65) }}
          </option></select
        ><small
          >没有可选片段时，可依据原始资料填写准确引文和位置。</small
        ></label
      >
      <div v-if="segmentTotal > 20" class="stage-actions">
        <el-button
          :disabled="segmentPage <= 1"
          @click="segmentPagination(segmentPage - 1)"
          >上一页片段</el-button
        >
        <span>片段第 {{ segmentPage }} 页</span>
        <el-button
          :disabled="segmentPage * 20 >= segmentTotal"
          @click="segmentPagination(segmentPage + 1)"
          >下一页片段</el-button
        >
      </div>
      <label class="stage-field"
        >位置说明<input
          v-model="locationLabel"
          placeholder="如：第 12 页 / 第一章"
          maxlength="255" /></label
      ><label class="stage-field"
        >引文<textarea
          v-model="quoteText"
          rows="4"
          placeholder="核对原文后填写"
        ></textarea></label
      ><el-button :disabled="!documentId || !quoteText.trim()" @click="add"
        >添加依据</el-button
      >
    </div>
  </section>
</template>
