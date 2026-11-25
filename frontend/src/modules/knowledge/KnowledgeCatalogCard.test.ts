import {expect,it} from 'vitest';
import {mount} from '@vue/test-utils';
import {createPinia} from 'pinia';
import {createMemoryHistory,createRouter} from 'vue-router';
import KnowledgeCatalogCard from './KnowledgeCatalogCard.vue';
import {libraryItem} from './libraryTestFixtures';
import type {KnowledgeType} from './api';

it.each<KnowledgeType>(['HERB','FORMULA','CONCEPT','EFFECT','SYMPTOM','MERIDIAN'])('renders %s with real fields and no invented picture',async type=>{
 const item=libraryItem('1',type);
 const router=createRouter({history:createMemoryHistory(),routes:[{path:'/:pathMatch(.*)*',component:{template:'<div />'}}]});
 await router.push('/knowledge');
 const wrapper=mount(KnowledgeCatalogCard,{props:{item},global:{plugins:[createPinia(),router]}});
 expect(wrapper.get('article').classes()).toContain('record-'+type.toLowerCase());
 expect(wrapper.get('h2 a').attributes('href')).toBe('/knowledge/1');
 expect(wrapper.text()).toContain(item.aliases[0]);
 expect(wrapper.findAll('img')).toHaveLength(0);
 if(type==='HERB')expect(wrapper.text()).toContain('药性字段');
 if(type==='FORMULA'){
  expect(wrapper.text()).toContain('功效字段');
  expect(wrapper.text()).toContain('出处字段');
  await wrapper.get('button').trigger('click');
  expect(wrapper.get('button').attributes('disabled')).toBeDefined();
  expect(wrapper.get('button').text()).toBe('已加入比较');
 }else expect(wrapper.get('.record-evidence').attributes('href')).toBe('/knowledge/1#knowledge-read-evidence');
 wrapper.unmount();
});
