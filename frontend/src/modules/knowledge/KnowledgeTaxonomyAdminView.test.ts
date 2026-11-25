import {afterEach,expect,it,vi} from 'vitest';
import {mount,flushPromises} from '@vue/test-utils';
import {createRouter,createMemoryHistory} from 'vue-router';
import KnowledgeTaxonomyAdminView from './KnowledgeTaxonomyAdminView.vue';
import {taxonomyApi} from './taxonomyApi';
afterEach(()=>vi.restoreAllMocks());
it('keeps unsaved configuration and its original optimistic version when a save conflicts',async()=>{
 const row={id:'1',code:'code',name:'原目录',type:'CONCEPT' as const,sortOrder:10,active:true,legacy:false,lockVersion:2};
 vi.spyOn(taxonomyApi,'directories').mockResolvedValue([row]);vi.spyOn(taxonomyApi,'tags').mockResolvedValue([]);
 const update=vi.spyOn(taxonomyApi,'updateDirectory').mockRejectedValue(new Error('目录已被更新，请重新核对'));
 const router=createRouter({history:createMemoryHistory(),routes:[{path:'/',component:KnowledgeTaxonomyAdminView}]});await router.push('/');
 const wrapper=mount(KnowledgeTaxonomyAdminView,{global:{plugins:[router]}});await flushPromises();
 await wrapper.get('button[aria-label="编辑 原目录"]').trigger('click');await wrapper.get('.taxonomy-edit input[maxlength]').setValue('保留我的输入');
 await wrapper.get('form').trigger('submit');await flushPromises();expect(update).toHaveBeenCalledWith('1',{name:'保留我的输入',active:true,sortOrder:10,lockVersion:2});expect((wrapper.get('.taxonomy-edit input[maxlength]').element as HTMLInputElement).value).toBe('保留我的输入');
 await wrapper.findAll('button').find(node=>node.text()==='重新载入配置')!.trigger('click');await flushPromises();expect((wrapper.get('.taxonomy-edit input[maxlength]').element as HTMLInputElement).value).toBe('保留我的输入');expect(wrapper.get('.taxonomy-edit select').attributes('disabled')).toBeDefined();wrapper.unmount();
});
