<script setup lang="ts">
import type { SourceDocument } from "./api";
import { stateLabels } from "./api";
import { warningText } from "./readerModes";
defineProps<{ summary: NonNullable<SourceDocument["processingSummary"]> }>();
const labels: Record<string, string> = {
  metadata: "书目信息",
  conversion: "格式转换",
  preview: "页面预览",
  structure: "正文结构",
  text: "可靠文字",
  media: "书内图片",
  storage: "原件保存",
};
function status(stage: unknown) {
  if (typeof stage === "string") return stateLabels[stage] || stage;
  if (stage && typeof stage === "object") {
    const record = stage as Record<string, unknown>;
    return (
      stateLabels[String(record.status || record.outcome || "")] ||
      String(record.status || record.outcome || "待核对")
    );
  }
  return "待核对";
}
</script>
<template>
  <section class="stage-section">
    <h2>资料处理结果</h2>
    <p>{{ stateLabels[summary.overallOutcome] || summary.overallOutcome }}</p>
    <dl class="stage-facts">
      <div v-for="(stage, name) in summary.stages" :key="name">
        <dt>{{ labels[name] || name }}</dt>
        <dd>{{ status(stage) }}</dd>
      </div>
    </dl>
    <p
      v-for="(warning, index) in summary.warnings"
      :key="index"
      class="stage-file-note"
    >
      {{ warningText(warning) }}
    </p>
    <p>
      可靠文字覆盖 {{ summary.units.filter((u) => u.textReliable).length }} /
      {{ summary.units.length }} 个单元。未提取的图像文字无法搜索或精确引用。
    </p>
  </section>
</template>
