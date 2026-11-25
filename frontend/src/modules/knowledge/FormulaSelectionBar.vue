<script setup lang="ts">
import {useFormulaShelf} from './formulaShelf';
defineProps<{always?:boolean}>();
const shelf=useFormulaShelf();
</script>
<template><section v-if="always || shelf.selected.length" class="formula-shelf" aria-label="已选方剂">
 <div class="formula-shelf-heading"><h2>已选方剂 <span>{{shelf.selected.length}} / 3</span></h2><button v-if="shelf.selected.length" type="button" class="library-text-action" @click="shelf.clear">清空选择</button></div>
 <ul v-if="shelf.selected.length"><li v-for="item in shelf.selected" :key="item.id"><span>{{item.canonicalName}}</span><button type="button" :aria-label="'移除 '+item.canonicalName" @click="shelf.remove(item.id)">×</button></li></ul>
 <p class="library-hint">{{shelf.selected.length<2?'至少选择2个方剂开始对照。':'最多3个方剂；移除后可替换。'}} 选择在当前账号内保留，刷新页面后清空。</p>
 <p v-if="shelf.notice" class="library-hint" role="status">{{shelf.notice}}</p>
 <slot><RouterLink class="library-secondary" to="/formulas/compare">前往比较工作台 →</RouterLink></slot>
</section></template>
