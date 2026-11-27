import {expect,it} from 'vitest';
import {mount,flushPromises} from '@vue/test-utils';
import {createPinia,setActivePinia} from 'pinia';
import {useSessionStore} from '../../shared/stores/session';
import SourceRead from './SourceRead.vue';
import type {SourceDocument} from './api';
it('separates public reading and download while respecting source availability',async()=>{
  const pinia=createPinia();setActivePinia(pinia);const session=useSessionStore();session.user={id:'reader',permissions:[]} as never;
  const item={id:'1',title:'版权边界',sourceType:'BOOK',availability:'ACTIVE',reviewState:'APPROVED',submittedBy:'owner',fileId:'f',readingEditionId:'e',rights:{licenseType:'AUTHORIZED',publicReadingAllowed:false,publicDownloadAllowed:false}} as SourceDocument;
  const wrapper=mount(SourceRead,{props:{item},global:{plugins:[pinia],stubs:{RouterLink:{props:['to'],template:'<a :href="to"><slot/></a>'},ElButton:{template:'<button><slot/></button>'}}}});
  expect(wrapper.find('a').exists()).toBe(false);expect(wrapper.find('button').exists()).toBe(false);
  await wrapper.setProps({item:{...item,rights:{...item.rights!,publicReadingAllowed:true}}});
  expect(wrapper.find('a').exists()).toBe(true);expect(wrapper.find('button').exists()).toBe(false);
  session.user={id:'reviewer',permissions:['knowledge:review']} as never;await flushPromises();expect(wrapper.find('button').exists()).toBe(true);
  await wrapper.setProps({item:{...item,availability:'SUSPENDED'}});expect(wrapper.find('a').exists()).toBe(false);expect(wrapper.find('button').exists()).toBe(false);
  wrapper.unmount();
});
