<script setup lang="ts">
import {computed} from 'vue';
import {typeLabels,type KnowledgeCatalogEntry} from './api';
import {useFormulaShelf} from './formulaShelf';
import {catalogDescription} from './library';
import {herbImage} from './herbImages';
const props=defineProps<{item:KnowledgeCatalogEntry}>();
const shelf=useFormulaShelf();
const description=computed(()=>catalogDescription(props.item));
const image=computed(()=>herbImage(props.item));
const manyAliases=computed(()=>(props.item.aliases?.join('、').length || 0)>48);
</script>
<template>
 <article class="library-record" :class="['record-'+item.type.toLowerCase(),{'has-image':image}]">
  <img v-if="image" class="record-image" :src="image.src" :alt="image.alt" loading="lazy" width="640" height="360"/>
  <header class="record-meta"><span class="record-type">类型：{{typeLabels[item.type]}}</span><small>第 {{item.versionNo}} 版</small><span v-if="item.categoryName" class="record-category">{{item.categoryLegacy?'历史目录':'目录'}}：{{item.categoryName}}</span></header>
  <div class="record-heading">
   <h2><RouterLink class="record-main-link" :to="'/knowledge/'+item.id">{{item.canonicalName}}</RouterLink></h2>
   <p v-if="item.title!==item.canonicalName" class="record-subtitle">{{item.title}}</p>
   <details v-if="item.aliases?.length && manyAliases" class="record-aliases"><summary>查看 {{item.aliases.length}} 个别名</summary><p>{{item.aliases.join('、')}}</p></details>
   <p v-else-if="item.aliases?.length" class="record-aliases">别名：{{item.aliases.join('、')}}</p>
  </div>
  <div class="record-content">
   <dl v-if="item.type==='HERB' && item.herb && (item.herb.nature || item.herb.flavor)" class="record-herb-facts"><div v-if="item.herb.nature"><dt>药性</dt><dd>{{item.herb.nature.length>36?'详见药材属性':item.herb.nature}}</dd></div><div v-if="item.herb.flavor"><dt>药味</dt><dd>{{item.herb.flavor.length>36?'详见药材属性':item.herb.flavor}}</dd></div></dl>
   <p v-if="description" class="record-description">{{description}}</p>
   <p v-if="item.type==='FORMULA' && item.formula?.originText" class="record-origin">出处：{{item.formula.originText}}</p>
   <p v-if="item.type==='FORMULA' && item.ingredients?.length" class="record-ingredients">组成：{{item.ingredients.slice(0,4).map(row=>row.herbName || '药材资料').join('、')}}<span v-if="item.ingredients.length>4"> 等 {{item.ingredients.length}} 味，详情查看完整组成</span></p>
   <nav v-if="item.relations?.length" class="record-relations" aria-label="关联知识"><RouterLink v-for="relation in item.relations.slice(0,2)" :key="relation.targetResourceId+relation.relationType" :to="'/knowledge/'+relation.targetResourceId">{{relation.targetName || '查看关联知识'}} ↗</RouterLink></nav>
  </div>
  <div v-if="item.tags?.length" class="knowledge-tag-links"><template v-for="tag in item.tags" :key="tag.id"><RouterLink v-if="tag.active" :to="{path:'/knowledge',query:{tagId:tag.id}}"># {{tag.name}}</RouterLink><span v-else># {{tag.name}}（已停用）</span></template></div>
  <footer><RouterLink class="record-read" :to="'/knowledge/'+item.id">阅读知识 →</RouterLink><button v-if="item.type==='FORMULA'" type="button" :disabled="shelf.includes(item.id) || shelf.selected.length>=3" @click="shelf.add(item)">{{shelf.includes(item.id)?'已加入比较':'加入比较'}}</button><RouterLink v-if="item.evidence?.length" class="record-evidence" :to="'/knowledge/'+item.id+'#knowledge-read-evidence'">来源：{{item.evidence[0]?.documentTitle || '查看来源依据'}}<span v-if="item.evidence.length>1"> 等 {{item.evidence.length}} 项</span></RouterLink></footer>
 </article>
</template>
