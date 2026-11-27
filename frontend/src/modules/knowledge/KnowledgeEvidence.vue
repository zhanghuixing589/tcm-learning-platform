<script setup lang="ts">
import type {Evidence} from './api';
import {evidenceReaderLink} from './readerSelection';
const props=defineProps<{evidence:Evidence[];versionNo?:number;anchorPrefix?:string;resourceId?:string;revisionId?:string}>();
function target(source:Evidence){return source.id&&props.resourceId&&props.revisionId?`/knowledge/${props.resourceId}/versions/${props.revisionId}/evidence/${source.id}`:evidenceReaderLink(source)}
</script>
<template>
 <p v-if="!evidence.length" class="muted">此版本未提供可展示的来源依据。</p>
 <ol v-else class="knowledge-evidence">
  <li v-for="(source,index) in evidence" :key="source.id || index" :id="anchorPrefix && anchorPrefix+'-evidence-'+(index+1)">
   <header><span class="evidence-number">依据 {{index+1}}</span><span v-if="versionNo" class="evidence-version">知识第 {{versionNo}} 版</span></header>
   <h3><RouterLink :to="target(source)">{{source.documentTitle || '查看来源资料'}}</RouterLink></h3>
   <p v-if="source.locationLabel" class="evidence-location">原文定位：{{source.locationLabel}}</p>
   <figure v-if="source.quoteText"><figcaption>原文摘录</figcaption><blockquote>{{source.quoteText}}</blockquote></figure>
   <RouterLink class="evidence-open" :to="target(source)">查看来源详情 ↗</RouterLink>
  </li>
 </ol>
</template>
<style scoped>
.knowledge-evidence{list-style:none;padding:0;margin:0;display:grid;gap:22px;}
.knowledge-evidence>li{padding-bottom:22px;border-bottom:1px solid var(--line);min-width:0;scroll-margin-top:calc(var(--portal-header-height,88px) + 24px);}
.evidence-version{font-size:14px;color:var(--muted);}
.knowledge-evidence>li:last-child{border-bottom:0;padding-bottom:0;}
header{display:flex;flex-wrap:wrap;gap:10px 18px;align-items:center;}
.evidence-number{color:var(--primary);font-size:14px;}
.knowledge-evidence h3{font:600 17px/1.7 var(--font-body);margin:8px 0;overflow-wrap:anywhere;}
.evidence-location{font-size:14px;color:var(--muted);margin:6px 0;overflow-wrap:anywhere;}
figure{margin:14px 0;}
figcaption{font-size:14px;color:var(--muted);margin-bottom:8px;}
.knowledge-evidence blockquote{margin:0;padding:12px 18px;border-left:2px solid var(--primary);background:var(--surface);color:var(--ink);white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.9;}
.evidence-open{display:inline-flex;align-items:center;min-height:44px;font-size:14px;}
</style>
