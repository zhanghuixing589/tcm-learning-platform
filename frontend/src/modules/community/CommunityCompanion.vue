<script setup lang="ts">
import { computed } from 'vue';
import './community-experience.css';
import type { SectionGroup } from './api';
import type { Post } from './types';
import UserAvatar from '../account/UserAvatar.vue';
const props=defineProps<{groups:SectionGroup[];posts:Post[]}>();
const authors=computed(()=>{
 const values=new Map<string,{person:Post['owner'];count:number}>();
 for(const item of props.posts){const found=values.get(item.owner.id);if(found)found.count++;else values.set(item.owner.id,{person:item.owner,count:1});}
 return [...values.values()].slice(0,3);
});
</script>
<template><aside class="community-companion" aria-label="社区发现与参与">
 <section class="community-rail-section community-sharing-note"><h2>一起维护好的讨论</h2><p>谈经验，也说出处；有不同见解时，围绕内容交流。正式知识可前往知识中心查阅。</p><RouterLink to="/knowledge">查阅正式知识 →</RouterLink></section>
 <section v-if="authors.length" class="community-rail-section"><h2>遇见分享者 <small>来自当前列表</small></h2><RouterLink v-for="{person,count} in authors" :key="person.id" class="community-author" :to="'/users/'+person.id"><UserAvatar :nickname="person.nickname" :url="person.avatarUrl"/><span><strong>{{person.nickname}}</strong><small>本页 {{count}} 篇分享</small></span><span aria-hidden="true">→</span></RouterLink></section>
</aside></template>
