import { afterEach, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import MarkdownEditor from './MarkdownEditor.vue';
import { testPolicy } from './testFixtures';
afterEach(()=>vi.restoreAllMocks());
it('keeps a selection, inserts bold and updates preview without a refresh click',async()=>{
 const wrapper=mount(MarkdownEditor,{props:{id:'body',modelValue:'所学',sourceFormat:'MARKDOWN' as const,policy:testPolicy,context:'new','onUpdate:modelValue':value=>wrapper.setProps({modelValue:value})}});
 const input=wrapper.get('textarea').element as HTMLTextAreaElement; input.setSelectionRange(0,2);
 await wrapper.get('button[aria-label="加粗"]').trigger('click');await flushPromises();
 expect(input.value).toBe('**所学**');expect(input.selectionStart).toBe(2);expect(input.selectionEnd).toBe(4);
 await wrapper.findAll('button').find(node=>node.text()==='预览正文')!.trigger('click');await flushPromises();expect(wrapper.get('.community-prose strong').text()).toBe('所学');
 await wrapper.setProps({modelValue:'## 新标题'});await flushPromises();expect(wrapper.get('.community-prose h2').text()).toBe('新标题');
 await wrapper.findAll('button').find(node=>node.text()==='编辑')!.trigger('click');expect(wrapper.find('button[aria-label="删除线"]').exists()).toBe(false);wrapper.unmount();
});
it('keeps policy-disabled tools unavailable and renders plain-text source literally',async()=>{
 const wrapper=mount(MarkdownEditor,{props:{id:'body',modelValue:'# 纯文本',sourceFormat:'PLAIN_TEXT',policy:testPolicy,context:'new'}});
 expect(wrapper.find('.markdown-toolbar').exists()).toBe(false);await wrapper.findAll('button').find(node=>node.text()==='预览正文')!.trigger('click');await flushPromises();
 expect(wrapper.find('.community-prose h1').exists()).toBe(false);expect(wrapper.get('.community-prose').text()).toBe('# 纯文本');wrapper.unmount();
});
