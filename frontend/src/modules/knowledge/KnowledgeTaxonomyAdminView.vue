<script setup lang="ts">
import {computed,ref,onMounted} from 'vue';
import {onBeforeRouteLeave} from 'vue-router';
import {typeLabels,type KnowledgeType} from './api';
import {taxonomyApi,type ManagedDirectory,type ManagedTag} from './taxonomyApi';
import {useWorkspace} from './useWorkspace';
import WorkspaceState from './WorkspaceState.vue';
import './stage.css';
const {loading,busy,error,actionError,message,load,act}=useWorkspace();
const directories=ref<ManagedDirectory[]>([]),tags=ref<ManagedTag[]>([]),mode=ref<'directory'|'tag'>('directory'),filterType=ref<KnowledgeType>('CONCEPT');
const editing=ref(''),version=ref(0),name=ref(''),sort=ref(10),active=ref(true),type=ref<KnowledgeType>('CONCEPT'),baseline=ref('');
const values=()=>JSON.stringify({name:name.value,sort:sort.value,active:active.value,type:type.value});
const dirty=computed(()=>!!baseline.value&&baseline.value!==values());
const visible=computed(()=>directories.value.filter(row=>!row.legacy&&row.type===filterType.value));
const legacy=computed(()=>directories.value.filter(row=>row.legacy));
function permittedChange(){return !dirty.value||window.confirm('当前配置尚未保存，是否放弃本次输入？');}
function reset(){editing.value='';name.value='';sort.value=10;active.value=true;type.value=filterType.value;version.value=0;baseline.value=values();}
function changeMode(next:'directory'|'tag'){if(busy.value||!permittedChange())return;mode.value=next;reset();}
function newItem(){if(!busy.value&&permittedChange())reset();}
function edit(row:ManagedDirectory|ManagedTag){if(busy.value||!permittedChange())return;editing.value=row.id;name.value=row.name;active.value=row.active;version.value=row.lockVersion;if('type'in row){type.value=row.type!;sort.value=row.sortOrder;}baseline.value=values();}
function refresh(){if(busy.value)return;void load(async()=>({directories:await taxonomyApi.directories(),tags:await taxonomyApi.tags()}),result=>{directories.value=result.directories;tags.value=result.tags;});}
function save(){const common={name:name.value.trim(),active:active.value};if(!common.name)return;
 void act(async()=>mode.value==='directory'?(editing.value?taxonomyApi.updateDirectory(editing.value,{...common,sortOrder:sort.value,lockVersion:version.value}):taxonomyApi.createDirectory({...common,type:type.value,sortOrder:sort.value})):(editing.value?taxonomyApi.updateTag(editing.value,{...common,lockVersion:version.value}):taxonomyApi.createTag(common)),row=>{
  if(mode.value==='directory'){const value=row as ManagedDirectory;directories.value=[...directories.value.filter(item=>item.id!==value.id),value].sort((a,b)=>a.sortOrder-b.sortOrder||Number(a.id)-Number(b.id));filterType.value=value.type!;}else{const value=row as ManagedTag;tags.value=[...tags.value.filter(item=>item.id!==value.id),value];}
  reset();
 },'配置已保存，前台重新查询时生效');
}
onMounted(()=>{reset();refresh();});
onBeforeRouteLeave(()=>!dirty.value||window.confirm('配置尚未保存，是否离开？'));
</script>
<template><section class="stage-page taxonomy-admin"><header class="stage-heading"><div><h1>知识目录与标签</h1><p>类型专属目录用于内容组织；通用标签可跨知识类型使用。</p></div><el-button :disabled="loading||busy" @click="refresh">重新载入配置</el-button></header>
 <nav class="stage-tabs" aria-label="分类配置"><button type="button" :disabled="busy" :aria-pressed="mode==='directory'" @click="changeMode('directory')">类型专属目录</button><button type="button" :disabled="busy" :aria-pressed="mode==='tag'" @click="changeMode('tag')">通用标签</button></nav>
 <WorkspaceState :loading="loading" :error="error" :action-error="actionError" :message="message" @retry="refresh">
  <div class="taxonomy-admin-layout"><section><div class="stage-toolbar"><label v-if="mode==='directory'">所属类型<select v-model="filterType" :disabled="busy"><option v-for="(label,key) in typeLabels" :key="key" :value="key">{{label}}</option></select></label><button type="button" class="library-secondary" :disabled="busy" @click="newItem">新增{{mode==='directory'?'目录':'标签'}}</button></div>
   <div class="stage-table-wrap"><table class="stage-table"><thead><tr><th>名称</th><th v-if="mode==='directory'">排序</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="row in mode==='directory'?visible:tags" :key="row.id"><td>{{row.name}}</td><td v-if="mode==='directory'">{{'sortOrder'in row?row.sortOrder:''}}</td><td>{{row.active?'已启用':'已停用'}}</td><td><button type="button" :aria-label="'编辑 '+row.name" :disabled="busy" @click="edit(row)">编辑</button></td></tr></tbody></table><p v-if="!(mode==='directory'?visible:tags).length" class="stage-hint">暂无配置，可新增后启用。</p></div>
   <details v-if="mode==='directory'&&legacy.length" class="stage-details"><summary>历史归档目录（{{legacy.length}}）</summary><p>保留原目录及知识版本引用，不再用于新筛选或写入；历史版本可继续追溯。</p><ul><li v-for="row in legacy" :key="row.id">{{row.name}} · 原编号 {{row.id}} · 只读归档</li></ul></details>
  </section><form class="taxonomy-edit" @submit.prevent="save"><h2>{{editing?'编辑':'新增'}}{{mode==='directory'?'目录':'标签'}}</h2><label v-if="mode==='directory'" class="stage-field">知识类型<select v-model="type" :disabled="!!editing||busy"><option v-for="(label,key) in typeLabels" :key="key" :value="key">{{label}}</option></select><small>建立后所属类型固定，避免改变既有知识归属。</small></label><label class="stage-field">名称（必填）<input v-model="name" required :maxlength="mode==='directory'?80:64" :disabled="busy"/></label><label v-if="mode==='directory'" class="stage-field">排序值<input v-model.number="sort" type="number" min="0" max="10000" required :disabled="busy"/><small>数值越小越靠前，同值按编号排序。</small></label><label class="taxonomy-enabled"><input v-model="active" type="checkbox" :disabled="busy"/>启用</label><p class="stage-hint">停用后不能用于新选择；已有正式知识和历史关联保留。</p><el-button type="primary" native-type="submit" :loading="busy" :disabled="!name.trim()">保存配置</el-button><p v-if="editing" class="stage-hint">按开始编辑时的版本保存。遇到冲突请重新载入列表并核对，不会覆盖当前输入。</p></form></div>
 </WorkspaceState>
</section></template>
<style scoped>
.taxonomy-admin-layout{display:grid;grid-template-columns:minmax(0,1fr) 320px;gap:32px;align-items:start}.taxonomy-edit{padding:24px;background:var(--paper);border:1px solid var(--line);border-radius:var(--radius)}.taxonomy-edit h2{font-size:20px;margin-top:0}.stage-table-wrap{overflow:auto}.stage-table td{overflow-wrap:anywhere}.taxonomy-enabled{display:flex;align-items:center;gap:8px;min-height:44px}.taxonomy-enabled input{width:18px;height:18px;accent-color:var(--primary)}button{font:inherit;font-size:14px;color:var(--primary);border:1px solid var(--line);border-radius:4px;background:var(--paper);padding:8px 12px;min-height:44px;cursor:pointer}.stage-tabs{display:flex;gap:12px;margin-bottom:24px}button[aria-pressed=true]{background:var(--primary-soft);border-color:var(--primary)}@media(max-width:1023px){.taxonomy-admin-layout{grid-template-columns:1fr}.taxonomy-edit{max-width:640px}}
</style>
