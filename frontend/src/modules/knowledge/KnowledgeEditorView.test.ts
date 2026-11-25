import {afterEach,expect,it,vi} from 'vitest';
import {mount,flushPromises} from '@vue/test-utils';
import {createRouter,createMemoryHistory} from 'vue-router';
import KnowledgeEditorView from './KnowledgeEditorView.vue';
import {stageApi} from './api';
afterEach(()=>vi.restoreAllMocks());
it('restricts authoring directories to the current type and clears the previous type choice',async()=>{
 vi.spyOn(stageApi,'categories').mockResolvedValue([{id:'c',code:'c',name:'基础理论',type:'CONCEPT'},{id:'h',code:'h',name:'解表药',type:'HERB'}]);
 vi.spyOn(stageApi,'tags').mockResolvedValue([{id:'t',name:'共享标签',active:true}]);
 const router=createRouter({history:createMemoryHistory(),routes:[{path:'/contribute/knowledge/:id',component:KnowledgeEditorView}]});await router.push('/contribute/knowledge/new');
 const wrapper=mount(KnowledgeEditorView,{global:{plugins:[router],stubs:{EvidenceEditor:true,FactsEditor:true,VersionCompare:true}}});await flushPromises();
 const category=()=>wrapper.findAll('select').find(node=>node.findAll('option').some(option=>option.text()==='未分类'))!;
 expect(category().text()).toContain('基础理论');expect(category().text()).not.toContain('解表药');await category().setValue('c');
 await wrapper.get('select[aria-label="知识类型"]').setValue('HERB');await flushPromises();expect(category().text()).toContain('解表药');expect(category().text()).not.toContain('基础理论');expect((category().element as HTMLSelectElement).selectedIndex).toBe(0);expect(wrapper.get('.taxonomy-tags').text()).toContain('共享标签');wrapper.unmount();
});
