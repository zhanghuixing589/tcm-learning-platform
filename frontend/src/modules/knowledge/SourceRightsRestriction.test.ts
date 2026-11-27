import {afterEach,expect,it,vi} from 'vitest';
import {mount,flushPromises} from '@vue/test-utils';
import * as api from './api';
import type {SourceDocument} from './api';
import SourceRightsRestriction from './SourceRightsRestriction.vue';
afterEach(()=>vi.restoreAllMocks());
function setup(){const item={id:'s',lockVersion:7,rightsStatus:'DECLARED',rights:{publicReadingAllowed:true,publicDownloadAllowed:false,quoteDisplayAllowed:true,ragUseAllowed:false}} as SourceDocument;return mount(SourceRightsRestriction,{props:{item},global:{stubs:{ElDialog:{props:['modelValue'],template:'<section v-if="modelValue"><slot/><slot name="footer"/></section>'}}}});}
it('only reduces granted scopes and sends the reviewed version with a reason',async()=>{
  const send=vi.spyOn(api,'post').mockResolvedValue({});const wrapper=setup();await wrapper.get('button').trigger('click');const checkboxes=wrapper.findAll('input');expect(checkboxes[1]!.attributes('disabled')).toBeDefined();expect(checkboxes[3]!.attributes('disabled')).toBeDefined();await checkboxes[0]!.setValue(false);await wrapper.get('textarea').setValue('仅保留合法引文');await wrapper.findAll('button').find(b=>b.text()==='确认收回授权')!.trigger('click');await flushPromises();
  expect(send).toHaveBeenCalledWith('/source-documents/s/rights/restrict',{expectedVersion:7,publicReadingAllowed:false,publicDownloadAllowed:false,quoteDisplayAllowed:true,ragUseAllowed:false,reason:'仅保留合法引文'});expect(wrapper.emitted('changed')).toHaveLength(1);wrapper.unmount();
});
it('preserves scope changes and reason after a version conflict',async()=>{
  vi.spyOn(api,'post').mockRejectedValue(new Error('版本已变化'));const wrapper=setup();await wrapper.get('button').trigger('click');await wrapper.findAll('input')[0]!.setValue(false);await wrapper.get('textarea').setValue('保留本地原因');await wrapper.findAll('button').find(b=>b.text()==='确认收回授权')!.trigger('click');await flushPromises();
  expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('保留本地原因');expect((wrapper.findAll('input')[0]!.element as HTMLInputElement).checked).toBe(false);expect(wrapper.find('[role="alert"]').exists()).toBe(true);expect(wrapper.emitted('changed')).toBeUndefined();wrapper.unmount();
});
