import { mount } from '@vue/test-utils';
import { afterEach, expect, it, vi } from 'vitest';
import CommunityOutline from './CommunityOutline.vue';
afterEach(()=>{vi.restoreAllMocks();document.querySelectorAll('[data-outline-test]').forEach(node=>node.remove());});
it('keeps the clicked section active when a short document cannot scroll it to the viewport top',async()=>{
 for(const id of ['first','second']){const heading=document.createElement('h2');heading.id=id;heading.dataset.outlineTest='true';heading.scrollIntoView=vi.fn();document.body.append(heading);}
 vi.spyOn(window,'requestAnimationFrame').mockImplementation(callback=>{callback(0);return 0;});
 const wrapper=mount(CommunityOutline,{props:{expanded:true,headings:[{id:'first',title:'重复标题',level:2},{id:'second',title:'重复标题',level:2}]}});
 await wrapper.findAll('a')[1].trigger('click');window.dispatchEvent(new Event('scroll'));expect(wrapper.findAll('a')[1].attributes('aria-current')).toBe('location');wrapper.unmount();
});
