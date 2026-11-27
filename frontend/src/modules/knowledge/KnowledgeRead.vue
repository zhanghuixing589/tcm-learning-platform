<script setup lang="ts">
import { typeLabels, type KnowledgeDetail } from "./api";
import {roleLabels,relationLabels} from './library';
import KnowledgeEvidence from './KnowledgeEvidence.vue';
defineProps<{ item: KnowledgeDetail; showTitle?: boolean; anchorPrefix?: string; hideAliases?: boolean }>();
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
    <p v-if="!hideAliases && item.aliases?.length" class="muted">
      别名：{{ item.aliases.join("、") }}
    </p>
    <p v-if="!anchorPrefix && item.categoryName" class="muted">{{item.categoryLegacy?'历史归档目录':'所属目录'}}：{{item.categoryName}}</p>
    <p v-if="!anchorPrefix && item.tags?.length" class="muted">通用标签：{{item.tags.map(tag=>tag.name+(tag.active?'':'（已停用）')).join('、')}}</p>
    <section :id="anchorPrefix && `${anchorPrefix}-body`"><h2 v-if="anchorPrefix">知识正文</h2><div class="stage-prose">{{ item.body }}</div></section>
    <section v-if="item.herb" :id="anchorPrefix && `${anchorPrefix}-herb`">
      <h2>药材属性</h2>
      <dl class="stage-facts herb-short-facts">
        <div>
          <dt>药性</dt>
          <dd>{{ item.herb.nature || "暂无资料" }}</dd>
        </div>
        <div>
          <dt>药味</dt>
          <dd>{{ item.herb.flavor || "暂无资料" }}</dd>
        </div>
      </dl>
      <dl class="stage-facts"><div><dt>来源与产地</dt><dd>{{ item.herb.originNote || "暂无资料" }}</dd></div></dl>
      <div v-if="item.herb.precautions" class="knowledge-precautions"><h3>注意事项</h3><p>{{item.herb.precautions}}</p></div>
    </section>
    <section v-if="item.formula" :id="anchorPrefix && `${anchorPrefix}-formula`">
      <h2>方剂信息</h2>
      <dl class="stage-facts">
        <div>
          <dt>出处</dt>
          <dd>{{ item.formula.originText || "暂无资料" }}</dd>
        </div>
        <div>
          <dt>功效</dt>
          <dd>{{ item.formula.functionSummary || "暂无资料" }}</dd>
        </div>
        <div>
          <dt>主治记载</dt>
          <dd>{{ item.formula.indicationSummary || "暂无资料" }}</dd>
        </div>
      </dl>
      <div v-if="item.formula.precautions" class="knowledge-precautions"><h3>注意事项</h3><p>{{item.formula.precautions}}</p></div>
    </section>
    <section v-if="item.ingredients?.length" :id="anchorPrefix && `${anchorPrefix}-ingredients`">
      <h2>方剂组成</h2>
      <ul class="stage-list">
        <li v-for="(row, index) in item.ingredients" :key="row.id || index">
          <RouterLink :to="`/knowledge/${row.herbResourceId}`">{{
            row.herbName || "查看药材"
          }}</RouterLink>
          <span class="stage-tag">{{ roleLabels[row.role] || row.role }}</span>
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
    <section :id="anchorPrefix && `${anchorPrefix}-evidence`">
      <h2>来源依据</h2>
      <KnowledgeEvidence :evidence="item.evidence || []" :resource-id="item.id" :revision-id="item.revisionId" :version-no="item.versionNo" :anchor-prefix="anchorPrefix"/>
    </section>
    <section v-if="item.relations?.length" :id="anchorPrefix && `${anchorPrefix}-relations`">
      <h2>相关知识</h2>
      <ul class="stage-list knowledge-relations">
        <li v-for="(row, index) in item.relations" :key="row.id || index">
          <RouterLink :to="`/knowledge/${row.targetResourceId}`">{{
            row.targetName || "查看关联知识"
          }}</RouterLink><span class="stage-tag">{{ relationLabels[row.relationType] || row.relationType }}</span>
          <p v-if="row.explanation">{{ row.explanation }}</p>
          <a v-if="anchorPrefix && item.evidence[row.evidenceIndex]" class="relation-evidence" :href="'#'+anchorPrefix+'-evidence-'+(row.evidenceIndex+1)">来源依据 {{ row.evidenceIndex + 1 }} →</a><small v-else class="muted">依据 {{ row.evidenceIndex + 1 }}</small>
        </li>
      </ul>
    </section>
  </article>
</template>
