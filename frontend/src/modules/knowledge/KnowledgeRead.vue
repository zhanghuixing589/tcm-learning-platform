<script setup lang="ts">
import { typeLabels, type KnowledgeDetail } from "./api";
defineProps<{ item: KnowledgeDetail; showTitle?: boolean }>();
</script>
<template>
  <article>
    <header v-if="showTitle" class="stage-section">
      <span class="stage-tag">{{ typeLabels[item.type] }}</span>
      <h2>{{ item.title }}</h2>
      <p class="muted">
        规范名称：{{ item.canonicalName }} · 第 {{ item.versionNo }} 版
      </p>
    </header>
    <p v-if="item.aliases?.length" class="muted">
      别名：{{ item.aliases.join("、") }}
    </p>
    <div class="stage-prose">{{ item.body }}</div>
    <section v-if="item.herb">
      <h2>药材属性</h2>
      <dl class="stage-facts">
        <div>
          <dt>药性</dt>
          <dd>{{ item.herb.nature || "未填写" }}</dd>
        </div>
        <div>
          <dt>药味</dt>
          <dd>{{ item.herb.flavor || "未填写" }}</dd>
        </div>
        <div>
          <dt>来源与产地</dt>
          <dd>{{ item.herb.originNote || "未填写" }}</dd>
        </div>
        <div>
          <dt>注意事项</dt>
          <dd>{{ item.herb.precautions || "未填写" }}</dd>
        </div>
      </dl>
    </section>
    <section v-if="item.formula">
      <h2>方剂信息</h2>
      <dl class="stage-facts">
        <div>
          <dt>出处</dt>
          <dd>{{ item.formula.originText || "未填写" }}</dd>
        </div>
        <div>
          <dt>功效</dt>
          <dd>{{ item.formula.functionSummary || "未填写" }}</dd>
        </div>
        <div>
          <dt>主治记载</dt>
          <dd>{{ item.formula.indicationSummary || "未填写" }}</dd>
        </div>
        <div>
          <dt>注意事项</dt>
          <dd>{{ item.formula.precautions || "未填写" }}</dd>
        </div>
      </dl>
    </section>
    <section v-if="item.ingredients?.length">
      <h2>方剂组成</h2>
      <ul class="stage-list">
        <li v-for="(row, index) in item.ingredients" :key="row.id || index">
          <RouterLink :to="`/knowledge/${row.herbResourceId}`">{{
            row.herbName || "查看药材"
          }}</RouterLink>
          <span class="stage-tag">{{ row.role }}</span>
          <p>
            {{
              row.doseText ||
              [row.doseValue, row.doseUnit]
                .filter((x) => x !== undefined && x !== null)
                .join(" ") ||
              "未记载用量"
            }}<span class="muted"> · 依据 {{ row.evidenceIndex + 1 }}</span>
          </p>
        </li>
      </ul>
    </section>
    <section>
      <h2>来源依据</h2>
      <p v-if="!item.evidence?.length" class="muted">
        此版本未提供可展示的来源依据。
      </p>
      <ol v-else class="stage-evidence">
        <li
          v-for="(evidence, index) in item.evidence"
          :key="evidence.id || index"
        >
          <RouterLink
            :to="`/sources/${evidence.documentId}${evidence.segmentId ? '#segment-' + evidence.segmentId : ''}`"
            >{{ evidence.documentTitle || "查看来源资料" }}</RouterLink><span
            v-if="evidence.locationLabel"
            class="muted"
          >
            · {{ evidence.locationLabel }}</span
          >
          <blockquote>{{ evidence.quoteText }}</blockquote>
        </li>
      </ol>
    </section>
    <section v-if="item.relations?.length">
      <h2>相关知识</h2>
      <ul class="stage-list">
        <li v-for="(row, index) in item.relations" :key="row.id || index">
          <RouterLink :to="`/knowledge/${row.targetResourceId}`">{{
            row.targetName || "查看关联知识"
          }}</RouterLink><span class="stage-tag">{{ row.relationType }}</span>
          <p v-if="row.explanation">{{ row.explanation }}</p>
          <small class="muted">依据 {{ row.evidenceIndex + 1 }}</small>
        </li>
      </ul>
    </section>
  </article>
</template>
