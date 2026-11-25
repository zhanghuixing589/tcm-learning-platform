<script setup lang="ts">
import {computed,nextTick,onBeforeUnmount,onMounted,ref,watch} from 'vue';
import {stageApi,type KnowledgeCatalogEntry,type KnowledgeDetail,type Ingredient} from './api';
import {useFormulaShelf} from './formulaShelf';
import FormulaSelectionBar from './FormulaSelectionBar.vue';
import WorkspaceState from './WorkspaceState.vue';
import PaginationBar from './PaginationBar.vue';
import {latestRequest} from '../account/personalCenter';
import {errorMessage} from '../../shared/api/client';
import {doseLabel,ingredientPresence,roleLabels} from './library';
import './stage.css';
import './library.css';
const shelf=useFormulaShelf();
const q=ref(''),applied=ref(''),page=ref(1),total=ref(0),options=ref<KnowledgeCatalogEntry[]>([]);
const loading=ref(false),error=ref(''),comparing=ref(false),compareError=ref(''),formulas=ref<KnowledgeDetail[]>([]);
const pageSize=6,searchRequests=latestRequest(),comparisonRequests=latestRequest();
const showCandidates=ref(true),differencesOnly=ref(false),resultRegion=ref<HTMLElement|null>(null),choiceRegion=ref<HTMLElement|null>(null);
const signature=computed(()=>shelf.selected.map(item=>item.id).join(','));
const presence=computed(()=>ingredientPresence(formulas.value));
function compositionKind(row:Ingredient){const count=presence.value.get(row.herbResourceId)?.size||0;return count===formulas.value.length?'common':count>1?'partial':'unique';}
function composition(row:Ingredient){return {common:'各方均有',partial:'部分共有',unique:'单方独有'}[compositionKind(row)];}
function visibleIngredients(formula:KnowledgeDetail){return (formula.ingredients||[]).filter(row=>!differencesOnly.value||compositionKind(row)!=='common');}
async function revealResults(){showCandidates.value=false;await nextTick();if(showCandidates.value || !formulas.value.length)return;resultRegion.value?.focus({preventScroll:true});resultRegion.value?.scrollIntoView?.({block:'start'});}
async function adjust(){showCandidates.value=true;await nextTick();choiceRegion.value?.focus({preventScroll:true});choiceRegion.value?.scrollIntoView?.({block:'start'});}
async function search(next=1){
 const ticket=searchRequests.begin();page.value=next;applied.value=q.value.trim();loading.value=true;error.value='';
 try{const result=await stageApi.knowledge({q:applied.value,type:'FORMULA',page:next,pageSize});if(searchRequests.isCurrent(ticket)){options.value=result.items;total.value=result.total;}}
 catch(cause){if(searchRequests.isCurrent(ticket))error.value=errorMessage(cause);}
 finally{if(searchRequests.isCurrent(ticket))loading.value=false;}
}
async function compare(){
 if(comparing.value||shelf.selected.length<2||shelf.selected.length>3)return;
 const ticket=comparisonRequests.begin(),ids=shelf.selected.map(item=>item.id);comparing.value=true;compareError.value='';formulas.value=[];
 try{const result=await stageApi.compare(ids);if(comparisonRequests.isCurrent(ticket)&&signature.value===ids.join(',')){formulas.value=result.formulas;differencesOnly.value=false;comparing.value=false;await revealResults();}}
 catch(cause){if(comparisonRequests.isCurrent(ticket)){compareError.value=errorMessage(cause);showCandidates.value=true;}}
 finally{if(comparisonRequests.isCurrent(ticket))comparing.value=false;}
}
watch(signature,()=>{comparisonRequests.invalidate();formulas.value=[];compareError.value='';comparing.value=false;showCandidates.value=true;differencesOnly.value=false;},{flush:'sync'});
onMounted(()=>search());
onBeforeUnmount(()=>{searchRequests.invalidate();comparisonRequests.invalidate();});
</script>
<template>
 <section class="stage-page formula-workbench">
  <RouterLink class="back-link" to="/knowledge?type=FORMULA">返回方剂目录</RouterLink>
  <header class="library-header"><div><p class="catalog-eyebrow">本草阅读馆 · 知识对照</p><h1>方剂比较工作台</h1><p>选择 2–3 个正式方剂，按原始记载对照组成与知识属性。</p></div></header>
  <FormulaSelectionBar always><button class="library-primary" type="button" :disabled="shelf.selected.length<2 || comparing" @click="compare">{{comparing?'正在比较…':'比较所选 '+shelf.selected.length+' 个方剂'}}</button></FormulaSelectionBar>
  <nav v-if="formulas.length" class="comparison-view-nav" aria-label="比较视图"><button type="button" :aria-current="showCandidates?'step':undefined" @click="adjust">选择方剂</button><button type="button" :aria-current="!showCandidates?'step':undefined" @click="revealResults">查看比较结果</button></nav>
  <section v-show="showCandidates" ref="choiceRegion" class="formula-choices" aria-label="调整比较对象" tabindex="-1">
  <form class="knowledge-search compare-search" role="search" aria-label="方剂检索" @submit.prevent="search()"><label for="formula-query">查找方剂</label><div><input id="formula-query" v-model="q" type="search" maxlength="100" placeholder="搜索方剂名称、别名或标题"/><button type="submit" class="library-secondary">查找</button></div></form>
  <WorkspaceState :loading="loading" :error="error" @retry="search(page)">
   <div class="library-result-bar"><h2>可选方剂</h2><span>{{total}} 项结果</span><button v-if="applied" type="button" class="library-text-action" @click="q='';search()">清除搜索</button></div>
   <ul v-if="options.length" class="formula-options"><li v-for="option in options" :key="option.id" :class="{'is-selected':shelf.includes(option.id)}"><div><span class="library-hint">{{option.categoryName || '未分类'}} · 第 {{option.versionNo}} 版</span><h3><RouterLink :to="'/knowledge/'+option.id">{{option.canonicalName}}</RouterLink></h3><p>{{option.formula?.originText || option.summary || '阅读方剂知识与来源依据。'}}</p></div><button type="button" :aria-label="(shelf.includes(option.id)?'移除 ':'选择 ')+option.canonicalName" :aria-pressed="shelf.includes(option.id)" :disabled="!shelf.includes(option.id)&&shelf.selected.length>=3" @click="shelf.includes(option.id)?shelf.remove(option.id):shelf.add(option)">{{shelf.includes(option.id)?'已选 · 移除':'选择'}}</button></li></ul>
   <div v-else class="library-empty"><h2>{{applied?'没有匹配的方剂':'尚无正式发布方剂'}}</h2><p>{{applied?'修改关键词或清除搜索后再试，已选方剂仍保留。':'方剂通过审核且来源有效后，将显示在这里。'}}</p><button v-if="applied" class="library-secondary" type="button" @click="q='';search()">清除搜索</button></div>
   <PaginationBar :page="page" :total="total" :page-size="pageSize" @change="search"/>
  </WorkspaceState>
  </section>
  <p v-if="comparing" role="status" class="library-hint">正在读取所选方剂的当前正式版本…</p>
  <div v-if="compareError" class="library-empty" role="alert"><h2>暂时无法比较</h2><p>{{compareError}}</p><p>请重试；若条目已失效，可移除后重新选择。</p><button type="button" class="library-secondary" :disabled="shelf.selected.length<2 || comparing" @click="compare">重试比较</button></div>
  <section v-if="formulas.length && !showCandidates" ref="resultRegion" class="formula-results" aria-label="方剂比较结果" tabindex="-1"><div class="library-result-bar"><h2>知识对照</h2><button type="button" class="library-secondary" @click="adjust">调整比较对象</button></div><div class="composition-legend" aria-label="组成标记图例"><span class="composition-badge is-common">各方均有</span><span class="composition-badge is-partial">部分共有</span><span class="composition-badge is-unique">单方独有</span><label><input v-model="differencesOnly" type="checkbox"/>仅查看差异组成</label></div><p class="library-hint">按药材 ID 标记组成；仅查看差异会隐藏共同药材，完整原始组成仍可切换查看。</p><div class="formula-table-scroll" tabindex="0" aria-label="方剂对照表，可横向滚动"><table class="formula-compare-table"><thead><tr><th scope="col">比较维度</th><th v-for="formula in formulas" :key="formula.id" scope="col"><RouterLink :to="'/knowledge/'+formula.id">{{formula.canonicalName}}</RouterLink><small>第 {{formula.versionNo}} 版</small></th></tr></thead><tbody>
   <tr><th scope="row">所属分类</th><td v-for="formula in formulas" :key="formula.id">{{formula.categoryName || '暂无资料'}}</td></tr>
   <tr><th scope="row">方剂出处</th><td v-for="formula in formulas" :key="formula.id">{{formula.formula?.originText || '暂无资料'}}</td></tr>
   <tr><th scope="row">组成药材</th><td v-for="formula in formulas" :key="formula.id"><ul v-if="visibleIngredients(formula).length" class="comparison-ingredients"><li v-for="(row,index) in visibleIngredients(formula)" :key="row.id || index"><RouterLink :to="'/knowledge/'+row.herbResourceId">{{row.herbName || '查看药材'}}</RouterLink><span class="composition-badge" :class="'is-'+compositionKind(row)">{{composition(row)}}</span><p>{{roleLabels[row.role] || row.role}} · {{doseLabel(row)}}</p><small v-if="Number.isInteger(row.evidenceIndex)">依据 {{row.evidenceIndex+1}}</small></li></ul><span v-else>{{differencesOnly && formula.ingredients?.length ? '无差异组成（共同组成已隐藏）' : '暂无资料'}}</span></td></tr>
   <tr><th scope="row">功效知识</th><td v-for="formula in formulas" :key="formula.id">{{formula.formula?.functionSummary || '暂无资料'}}</td></tr>
   <tr><th scope="row">主治记载</th><td v-for="formula in formulas" :key="formula.id">{{formula.formula?.indicationSummary || '暂无资料'}}</td></tr>
   <tr><th scope="row">注意事项</th><td v-for="formula in formulas" :key="formula.id">{{formula.formula?.precautions || '暂无资料'}}</td></tr>
   <tr><th scope="row">参考来源</th><td v-for="formula in formulas" :key="formula.id"><ul v-if="formula.evidence?.length" class="comparison-evidence"><li v-for="(evidence,index) in formula.evidence" :key="evidence.id || index"><RouterLink :to="'/sources/'+evidence.documentId">{{evidence.documentTitle || '查看来源资料'}}</RouterLink><p v-if="evidence.locationLabel">{{evidence.locationLabel}}</p></li></ul><span v-else>暂无资料</span></td></tr>
  </tbody></table></div></section>
  <p class="library-study-note">仅用于中医药知识学习与资料对照，不用于诊断、选择个人用药或判断哪种方剂更适合个人。</p>
 </section>
</template>
