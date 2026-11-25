import {afterEach,beforeEach,expect,it,vi} from 'vitest';
import {mount,flushPromises} from '@vue/test-utils';
import {createPinia} from 'pinia';
import {createMemoryHistory,createRouter} from 'vue-router';
import {stageApi} from './api';
import KnowledgeListView from './KnowledgeListView.vue';
import {libraryItem} from './libraryTestFixtures';
beforeEach(()=>{vi.spyOn(stageApi,'tags').mockResolvedValue([]);});
afterEach(()=>vi.restoreAllMocks());
it('loads only the selected type directories and clears category and page while keeping cross-type tags',async()=>{
 const categories=vi.spyOn(stageApi,'categories').mockImplementation(async type=>[{id:type==='HERB'?'h':'f',type:type as 'HERB'|'FORMULA',name:type==='HERB'?'解表药':'解表剂',code:'test'}]);
 vi.spyOn(stageApi,'tags').mockResolvedValue([{id:'t',name:'跨类型',active:true}]);
 vi.spyOn(stageApi,'knowledge').mockResolvedValue({items:[],total:0,page:1,pageSize:12});
 const {wrapper,router}=await setup('/knowledge?type=HERB&categoryId=h&page=2&tagId=t');
 expect(categories).toHaveBeenLastCalledWith('HERB');expect(wrapper.get('.knowledge-directory-bar').text()).toContain('解表药');expect(wrapper.text()).not.toContain('解表剂');
 await wrapper.get('.library-type-nav a[href*="type=FORMULA"]').trigger('click');await flushPromises();
 expect(router.currentRoute.value.query.categoryId).toBeUndefined();expect(router.currentRoute.value.query.page).toBeUndefined();expect(router.currentRoute.value.query.tagId).toBe('t');expect(categories).toHaveBeenLastCalledWith('FORMULA');expect(wrapper.get('.knowledge-directory-bar').text()).toContain('解表剂');
 await wrapper.get('.library-type-nav a').trigger('click');await flushPromises();expect(wrapper.find('.knowledge-directory-bar').exists()).toBe(false);wrapper.unmount();
});
it('distinguishes an empty directory from a keyword no-match and normalizes an old category-only URL',async()=>{
 vi.spyOn(stageApi,'categories').mockResolvedValue([{id:'h',name:'解表药',code:'herb',type:'HERB'}]);vi.spyOn(stageApi,'knowledge').mockResolvedValue({items:[],total:0,page:1,pageSize:12});
 const {wrapper,router}=await setup('/knowledge?type=HERB&categoryId=h');expect(wrapper.text()).toContain('该目录暂无知识');
 await wrapper.get('#library-query').setValue('查无结果');await wrapper.get('form').trigger('submit');await flushPromises();expect(wrapper.text()).toContain('没有匹配的知识');
 await router.push('/knowledge?categoryId=h&page=3');await flushPromises();expect(router.currentRoute.value.query.categoryId).toBeUndefined();expect(router.currentRoute.value.query.page).toBeUndefined();expect(wrapper.text()).toContain('尚无正式发布知识');wrapper.unmount();
});
async function setup(path='/knowledge'){const router=createRouter({history:createMemoryHistory(),routes:[{path:'/knowledge',component:KnowledgeListView},{path:'/formulas/compare',component:{template:'<div />'}}]});await router.push(path);const wrapper=mount(KnowledgeListView,{global:{plugins:[createPinia(),router]}});await flushPromises();return {wrapper,router};}
it('distinguishes no published data from a filtered empty result and uses only one type selector',async()=>{vi.spyOn(stageApi,'categories').mockResolvedValue([{id:'9',name:'真实分类',code:'real'}]);vi.spyOn(stageApi,'knowledge').mockResolvedValue({items:[],total:0,page:1,pageSize:12});const {wrapper,router}=await setup();expect(wrapper.text()).toContain('尚无正式发布知识');expect(wrapper.findAll('select')).toHaveLength(0);expect(wrapper.findAll('.library-type-nav a')).toHaveLength(7);await wrapper.get('#library-query').setValue('  别名  ');await wrapper.get('form').trigger('submit');await flushPromises();expect(router.currentRoute.value.query.q).toBe('别名');expect(wrapper.text()).toContain('没有匹配的知识');wrapper.unmount();});
it('keeps the latest filter response and never requests individual details to enrich cards',async()=>{vi.spyOn(stageApi,'categories').mockResolvedValue([]);let old!:(value:any)=>void;vi.spyOn(stageApi,'knowledge').mockImplementationOnce(()=>new Promise(resolve=>old=resolve)).mockResolvedValue({items:[libraryItem('new','HERB')],total:1,page:1,pageSize:12});const detail=vi.spyOn(stageApi,'detail');const {wrapper,router}=await setup();await router.push('/knowledge?type=HERB');await flushPromises();old({items:[libraryItem('old')],total:1});await flushPromises();expect(wrapper.text()).toContain('验收名称new');expect(wrapper.text()).not.toContain('验收名称old');expect(wrapper.text()).toContain('药性字段');expect(detail).not.toHaveBeenCalled();wrapper.unmount();});
