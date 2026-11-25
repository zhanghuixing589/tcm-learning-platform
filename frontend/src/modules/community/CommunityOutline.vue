<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue';
import type { CommunityHeading } from './reading';
const props=defineProps<{headings:CommunityHeading[];expanded?:boolean}>();
const active=ref('');let frame=0;let requested='';let requestedScroll=0;
function update(){if(requested && Math.abs(scrollY-requestedScroll)<2 && props.headings.some(heading=>heading.id===requested)){active.value=requested;return;}requested='';const boundary=(document.querySelector('.site-header')?.getBoundingClientRect().bottom||0)+40;let id=props.headings[0]?.id||'';for(const heading of props.headings){const node=document.getElementById(heading.id);if(node && node.getBoundingClientRect().top<=boundary)id=heading.id;}active.value=id;}
function onScroll(){if(frame)return;frame=requestAnimationFrame(()=>{frame=0;update();});}
function go(id:string){const node=document.getElementById(id);if(!node)return;active.value=id;node.tabIndex=-1;node.scrollIntoView({block:'start',behavior:'instant'});requested=id;requestedScroll=scrollY;node.focus({preventScroll:true});history.replaceState(history.state,'','#'+id);}
onMounted(()=>{update();window.addEventListener('scroll',onScroll,{passive:true});});
onBeforeUnmount(()=>{window.removeEventListener('scroll',onScroll);if(frame)cancelAnimationFrame(frame);});
watch(()=>props.headings,update,{flush:'post'});
</script>
<template><details class="reader-toc" :open="expanded"><summary>本文目录 <span>{{headings.length}} 节</span></summary><nav aria-label="正文目录"><a v-for="heading in headings" :key="heading.id" :class="{'toc-child':heading.level>2}" :aria-current="active===heading.id?'location':undefined" :href="'#'+heading.id" @click.prevent="go(heading.id)">{{heading.title}}</a></nav></details></template>
