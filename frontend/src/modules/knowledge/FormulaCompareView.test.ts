import {afterEach,expect,it,vi} from 'vitest';
import {mount,flushPromises} from '@vue/test-utils';
import {createPinia,setActivePinia} from 'pinia';
import {stageApi} from './api';
import FormulaCompareView from './FormulaCompareView.vue';
import {useFormulaShelf} from './formulaShelf';
import {libraryItem} from './libraryTestFixtures';
afterEach(()=>vi.restoreAllMocks());
const globalFor=(pinia:ReturnType<typeof createPinia>)=>({plugins:[pinia],stubs:{RouterLink:{props:['to'],template:'<a :href="to"><slot /></a>'}}});
it('keeps picks while searching and rejects a late comparison after selection changes',async()=>{const pinia=createPinia();setActivePinia(pinia);const shelf=useFormulaShelf();shelf.add(libraryItem('1','FORMULA'));shelf.add(libraryItem('2','FORMULA'));vi.spyOn(stageApi,'knowledge').mockResolvedValue({items:[],total:0,page:1,pageSize:6});let resolve!:(value:any)=>void;const compare=vi.spyOn(stageApi,'compare').mockImplementation(()=>new Promise(done=>resolve=done));const wrapper=mount(FormulaCompareView,{attachTo:document.body,global:globalFor(pinia)});await flushPromises();await wrapper.get('#formula-query').setValue('搜索另一个');await wrapper.get('form').trigger('submit');await flushPromises();expect(shelf.selected).toHaveLength(2);await wrapper.findAll('button').find(node=>node.text().includes('比较所选'))!.trigger('click');expect(compare).toHaveBeenCalledWith(['1','2']);shelf.remove('2');await flushPromises();resolve({formulas:[libraryItem('1','FORMULA'),libraryItem('2','FORMULA')]});await flushPromises();expect(wrapper.find('table').exists()).toBe(false);expect(wrapper.findAll('button').find(node=>node.text().includes('比较所选'))!.attributes('disabled')).toBeDefined();wrapper.unmount();});
it('renders shared ingredients by resource ID and leaves missing attributes honest',async()=>{const pinia=createPinia();setActivePinia(pinia);const shelf=useFormulaShelf();const a=libraryItem('1','FORMULA'),b=libraryItem('2','FORMULA');a.ingredients=[{herbResourceId:'H',herbRevisionId:'v1',herbName:'同一药材',role:'JUN',doseText:'原始用量',evidenceIndex:0}];b.ingredients=[{...a.ingredients[0],herbRevisionId:'v2'}];shelf.add(a);shelf.add(b);vi.spyOn(stageApi,'knowledge').mockResolvedValue({items:[],total:0,page:1,pageSize:6});vi.spyOn(stageApi,'compare').mockResolvedValue({formulas:[a,b]});const wrapper=mount(FormulaCompareView,{attachTo:document.body,global:globalFor(pinia)});await flushPromises();await wrapper.findAll('button').find(node=>node.text().includes('比较所选'))!.trigger('click');await flushPromises();expect(wrapper.findAll('table .composition-badge.is-common')).toHaveLength(2);expect(wrapper.text()).toContain('原始用量');expect(wrapper.text()).toContain('暂无资料');wrapper.unmount();});

it('moves to results, restores the selection view and filters only common ingredient IDs',async()=>{
 const pinia=createPinia();setActivePinia(pinia);const shelf=useFormulaShelf();
 const items=['1','2','3'].map(id=>libraryItem(id,'FORMULA'));
 const ingredient=(id:string)=>({herbResourceId:id,herbRevisionId:'r'+id,herbName:'相同展示名',role:'JUN',doseText:'原始记载'+id,evidenceIndex:0});
 items[0].ingredients=[ingredient('common'),ingredient('partial'),ingredient('uniqueA')];
 items[1].ingredients=[ingredient('common'),ingredient('partial'),ingredient('uniqueB')];
 items[2].ingredients=[ingredient('common')];items.forEach(item=>shelf.add(item));
 vi.spyOn(stageApi,'knowledge').mockResolvedValue({items:[],total:0,page:1,pageSize:6});
 let finish!:(value:{formulas:typeof items})=>void;
 const compare=vi.spyOn(stageApi,'compare').mockImplementation(()=>new Promise(done=>finish=done));
 const wrapper=mount(FormulaCompareView,{attachTo:document.body,global:globalFor(pinia)});await flushPromises();
 await wrapper.get('#formula-query').setValue('保留的搜索');
 const button=()=>wrapper.findAll('button').find(node=>node.text().includes('比较所选'))!;
 const primary=button();await primary.trigger('click');await primary.trigger('click');expect(compare).toHaveBeenCalledTimes(1);finish({formulas:items});await flushPromises();
 expect(wrapper.find('.formula-choices').isVisible()).toBe(false);
 expect(wrapper.findAll('table .is-common')).toHaveLength(3);
 expect(wrapper.findAll('table .is-partial')).toHaveLength(2);
 expect(wrapper.findAll('table .is-unique')).toHaveLength(2);
 await wrapper.get('input[type=checkbox]').setValue(true);
 expect(wrapper.findAll('table .is-common')).toHaveLength(0);
 expect(wrapper.text()).toContain('无差异组成（共同组成已隐藏）');expect(items[2].ingredients).toHaveLength(1);
 await wrapper.findAll('button').find(node=>node.text()==='调整比较对象')!.trigger('click');
 expect((wrapper.get('#formula-query').element as HTMLInputElement).value).toBe('保留的搜索');expect(shelf.selected).toHaveLength(3);
 expect(wrapper.find('.formula-choices').isVisible()).toBe(true);
 shelf.remove('3');await flushPromises();expect(wrapper.find('.comparison-view-nav').exists()).toBe(false);expect(wrapper.find('table').exists()).toBe(false);wrapper.unmount();
});

it('reopens adjustment after a failed repeat comparison without losing the account selection',async()=>{
 const pinia=createPinia();setActivePinia(pinia);const shelf=useFormulaShelf();
 const items=['1','2'].map(id=>libraryItem(id,'FORMULA'));items.forEach(item=>shelf.add(item));
 vi.spyOn(stageApi,'knowledge').mockResolvedValue({items:[],total:0,page:1,pageSize:6});
 vi.spyOn(stageApi,'compare').mockResolvedValueOnce({formulas:items}).mockRejectedValueOnce(new Error('重复比较请求失败'));
 const wrapper=mount(FormulaCompareView,{attachTo:document.body,global:globalFor(pinia)});await flushPromises();
 const primary=wrapper.get('.formula-shelf .library-primary');await primary.trigger('click');await flushPromises();expect(wrapper.find('table').exists()).toBe(true);
 await primary.trigger('click');await flushPromises();expect(wrapper.find('table').exists()).toBe(false);expect(wrapper.find('.formula-choices').isVisible()).toBe(true);expect(wrapper.text()).toContain('重复比较请求失败');expect(shelf.selected).toHaveLength(2);wrapper.unmount();
});
