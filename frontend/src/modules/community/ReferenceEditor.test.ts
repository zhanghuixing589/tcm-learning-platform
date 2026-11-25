import { afterEach, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import ReferenceEditor from './ReferenceEditor.vue';
afterEach(()=>vi.restoreAllMocks());
it('confirms deletion and keeps reference numbers monotonic',async()=>{
 const wrapper=mount(ReferenceEditor,{props:{modelValue:[],maxReferences:3,maxReferenceChars:10000,'onUpdate:modelValue':value=>wrapper.setProps({modelValue:value})}});
 await wrapper.get('button.reference-add').trigger('click');await flushPromises();
 expect(wrapper.props('modelValue')[0].displayNumber).toBe(1);
 vi.spyOn(window,'confirm').mockReturnValue(false);
 await wrapper.findAll('button').find(node=>node.text()==='删除文献 1')!.trigger('click');expect(wrapper.props('modelValue')).toHaveLength(1);
 vi.mocked(window.confirm).mockReturnValue(true);await wrapper.findAll('button').find(node=>node.text()==='删除文献 1')!.trigger('click');await flushPromises();
 expect(window.confirm).toHaveBeenCalledWith(expect.stringContaining('[1]'));expect(wrapper.props('modelValue')).toHaveLength(0);
 await wrapper.get('button.reference-add').trigger('click');await flushPromises();expect(wrapper.props('modelValue')[0].displayNumber).toBe(2);wrapper.unmount();
});
