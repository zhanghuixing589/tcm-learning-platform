import {expect,it} from 'vitest';
import {mount} from '@vue/test-utils';
import KnowledgeEvidence from './KnowledgeEvidence.vue';
import {herbImage} from './herbImages';

it('renders only supplied evidence as text, with a real source route and knowledge version',()=>{
 const wrapper=mount(KnowledgeEvidence,{props:{versionNo:4,anchorPrefix:'read',evidence:[{documentId:'8',documentTitle:'真实来源',segmentId:'3',locationLabel:'卷一，第2页',quoteText:'<img src=x onerror=alert(1)> **原文**'}]},global:{stubs:{RouterLink:{props:['to'],template:'<a :href="to"><slot /></a>'}}}});
 expect(wrapper.text()).toContain('知识第 4 版');expect(wrapper.text()).toContain('卷一，第2页');
 expect(wrapper.get('blockquote').text()).toBe('<img src=x onerror=alert(1)> **原文**');
 expect(wrapper.find('img').exists()).toBe(false);
 expect(wrapper.get('h3 a').attributes('href')).toBe('/sources/8#segment-3');
 expect(wrapper.get('li').attributes('id')).toBe('read-evidence-1');wrapper.unmount();
});
it('shows no fabricated source when evidence is absent',()=>{
 const wrapper=mount(KnowledgeEvidence,{props:{evidence:[]}});expect(wrapper.text()).toContain('未提供可展示的来源依据');expect(wrapper.findAll('li')).toHaveLength(0);wrapper.unmount();
});
it('matches permitted images only to the exact canonical herb name',()=>{
 for(const canonicalName of ['黄芪','陈皮'])expect(herbImage({type:'HERB',canonicalName})?.alt).toBe(canonicalName+'药材参考图');
 for(const canonicalName of ['验收药材甲','黄芪片','黄芪与陈皮','constructor'])expect(herbImage({type:'HERB',canonicalName})).toBeUndefined();
 expect(herbImage({type:'CONCEPT',canonicalName:'黄芪'})).toBeUndefined();
});
