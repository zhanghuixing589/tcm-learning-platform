<script setup lang="ts">
import {computed,ref,watch} from 'vue';
import {useRoute,useRouter} from 'vue-router';
import {stageApi,typeLabels,type Category,type KnowledgeTag,type KnowledgeCatalogEntry} from './api';
import {useWorkspace} from './useWorkspace';
import WorkspaceState from './WorkspaceState.vue';
import PaginationBar from './PaginationBar.vue';
import KnowledgeCatalogCard from './KnowledgeCatalogCard.vue';
import KnowledgeTypeIcon from './KnowledgeTypeIcon.vue';
import FormulaSelectionBar from './FormulaSelectionBar.vue';
import './stage.css';
import './library.css';
const route=useRoute(),router=useRouter();
const {loading,error,load}=useWorkspace();
const items=ref<KnowledgeCatalogEntry[]>([]),categories=ref<Category[]>([]),total=ref(0);
const q=ref(''),categoryId=ref(''),tagId=ref(''),tags=ref<KnowledgeTag[]>([]),categoryError=ref(false),tagsError=ref(false);
const pageSize=12;
const page=computed(()=>Math.max(1,Math.floor(Number(route.query.page)||1)));
const currentType=computed(()=>String(route.query.type||''));
const currentLabel=computed(()=>typeLabels[currentType.value as keyof typeof typeLabels]||'全部知识');
const visibleCategories=computed(()=>categories.value.filter(category=>category.type===currentType.value));
const hasSearch=computed(()=>!!String(route.query.q||'').trim()||!!route.query.tagId);
const emptyTitle=computed(()=>hasSearch.value?'没有匹配的知识':route.query.categoryId?'该目录暂无知识':currentType.value?'该类型暂无知识':'尚无正式发布知识');
const filtered=computed(()=>!!String(route.query.q||'').trim()||!!route.query.categoryId||!!currentType.value||!!route.query.tagId);
const descriptions:Record<string,string>={CONCEPT:'定义与关联',HERB:'药性与来源',FORMULA:'组成与对照',EFFECT:'功效知识',SYMPTOM:'症状知识',MERIDIAN:'经络知识'};
async function search(next=1){
 const before=route.fullPath;
 await router.push({path:'/knowledge',query:{q:q.value.trim()||undefined,type:currentType.value||undefined,categoryId:currentType.value?categoryId.value||undefined:undefined,tagId:tagId.value||undefined,page:next>1?next:undefined}});
 if(route.fullPath===before)refresh();
}
function typeQuery(type=''){return {path:'/knowledge',query:{type:type||undefined,q:String(route.query.q||'').trim()||undefined,tagId:String(route.query.tagId||'')||undefined}};}
function refresh(){
 if(!currentType.value && route.query.categoryId){void router.replace({path:'/knowledge',query:{...route.query,categoryId:undefined,page:undefined}});return;}
 q.value=String(route.query.q||'');categoryId.value=String(route.query.categoryId||'');tagId.value=String(route.query.tagId||'');
 const params={q:q.value,type:currentType.value,categoryId:categoryId.value,tagId:tagId.value,page:page.value,pageSize};
 void load(async()=>{
  const [list,classification,tagList]=await Promise.allSettled([stageApi.knowledge(params),currentType.value?stageApi.categories(currentType.value):Promise.resolve([] as Category[]),stageApi.tags()]);
  if(list.status==='rejected')throw list.reason;
  return {list:list.value,classification,tagList};
 },result=>{tags.value=result.tagList.status==='fulfilled'?result.tagList.value:[];tagsError.value=result.tagList.status==='rejected';items.value=result.list.items;total.value=result.list.total;categoryError.value=result.classification.status==='rejected';categories.value=result.classification.status==='fulfilled'?result.classification.value:[];});
}
watch(()=>route.fullPath,refresh,{immediate:true});
</script>
<template>
 <section class="stage-page knowledge-catalog knowledge-library">
  <header class="library-header"><div><p class="catalog-eyebrow">本草阅读馆 · 知识中心</p><h1>知识中心</h1><p>查阅药材、方剂与中医知识，在结构化事实与原始依据之间探索。</p></div><RouterLink class="library-secondary" to="/formulas/compare">方剂比较工作台 ↗</RouterLink></header>
  <form class="knowledge-search" role="search" aria-label="知识检索" @submit.prevent="search()"><label for="library-query">搜索知识</label><div><input id="library-query" v-model="q" type="search" maxlength="100" placeholder="搜索规范名称、别名或标题"/><button type="submit" class="library-primary">搜索</button></div></form>
  <div class="library-layout">
   <aside class="library-navigation"><h2>知识类型</h2><nav class="library-type-nav" aria-label="知识类型目录"><RouterLink :to="typeQuery()" :aria-current="!currentType?'page':undefined"><KnowledgeTypeIcon/><span>全部知识</span><span class="type-indicator" aria-hidden="true">›</span></RouterLink><RouterLink v-for="(label,key) in typeLabels" :key="key" :to="typeQuery(key)" :aria-current="currentType===key?'page':undefined"><KnowledgeTypeIcon :kind="key"/><span><strong>{{label}}</strong><small>{{descriptions[key]}}</small></span><span class="type-indicator" aria-hidden="true">›</span></RouterLink></nav><p class="library-hint">仅展示当前可见、来源有效的正式知识。</p></aside>
   <div class="library-main">
    <div class="library-result-bar"><div><h2>{{currentLabel}}</h2><span v-if="!loading && !error">{{total}} 项结果 · 按最近更新</span></div><label v-if="tags.length" class="library-category">通用标签<select v-model="tagId" @change="search()"><option value="">全部标签</option><option v-for="tag in tags" :key="tag.id" :value="tag.id">{{tag.name}}</option></select></label><RouterLink v-if="filtered" class="library-text-action" to="/knowledge">清除筛选</RouterLink></div>
    <nav v-if="currentType" class="knowledge-directory-bar" aria-label="类型专属目录"><button type="button" :aria-pressed="!categoryId" @click="categoryId='';search()">全部目录</button><button v-for="category in visibleCategories" :key="category.id" type="button" :aria-pressed="categoryId===category.id" @click="categoryId=category.id;search()">{{category.name}}</button></nav>
    <p v-if="tagsError" class="library-hint" role="status">通用标签暂未加载。<button class="library-text-action" type="button" @click="refresh">重试</button></p>
    <p v-if="categoryError" role="status" class="library-hint">目录分类暂未加载，知识结果仍可阅读。<button class="library-text-action" type="button" @click="refresh">重试</button></p>
    <FormulaSelectionBar/>
    <WorkspaceState :loading="loading" :error="error" @retry="refresh">
     <div v-if="items.length" class="library-records" :class="'is-'+(currentType.toLowerCase()||'all')"><KnowledgeCatalogCard v-for="item in items" :key="item.id" :item="item"/></div>
     <div v-else class="library-empty"><h3>{{emptyTitle}}</h3><p>{{hasSearch?'可修改关键词或通用标签后再试。':currentType?'当前目录暂未收录可见的正式知识，可切换目录或查看其他知识类型。':'知识通过审核且来源有效后，会出现在这里。你也可以先去社区交流学习发现。'}}</p><RouterLink v-if="filtered" class="library-secondary" to="/knowledge">清除筛选</RouterLink><RouterLink v-else class="library-secondary" to="/community">浏览科普社区 →</RouterLink></div>
     <PaginationBar :page="page" :total="total" :page-size="pageSize" @change="search"/>
    </WorkspaceState>
   </div>
  </div>
 </section>
</template>
