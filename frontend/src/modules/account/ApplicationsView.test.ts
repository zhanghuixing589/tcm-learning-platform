import {afterEach,expect,it,vi} from 'vitest';
import {flushPromises,mount,type VueWrapper} from '@vue/test-utils';
import {createPinia} from 'pinia';
import {api} from '../../shared/api/client';
import {useSessionStore} from '../../shared/stores/session';
import type {RoleApplication,User} from '../../shared/api/types';
import ApplicationsView from './ApplicationsView.vue';
import AdminApplicationsView from '../admin/AdminApplicationsView.vue';
import AdminUsersView from '../admin/AdminUsersView.vue';

const wrappers:VueWrapper[]=[];
afterEach(()=>{wrappers.splice(0).forEach(w=>w.unmount());vi.restoreAllMocks();});
const application=(id='68',status='PENDING')=>({id,userId:'970',username:'demo_admin',roleCode:'CONTRIBUTOR',statement:'完整申请说明\n第二段',status,reviewNote:status==='PENDING'?null:'审核意见全文',reviewerId:status==='PENDING'?null:'1',reviewedAt:status==='PENDING'?null:'2026-11-27T01:00:00Z',createdAt:'2026-11-25T07:40:00Z'} as RoleApplication);
const envelope=<T>(items:T[],total=items.length)=>({data:{data:{items,total,page:1,pageSize:20}}});
function setup(component:typeof ApplicationsView|typeof AdminApplicationsView|typeof AdminUsersView){
 const pinia=createPinia();const session=useSessionStore(pinia);
 session.user={id:'970',username:'demo_admin',nickname:'管理员',bio:null,status:'ACTIVE',createdAt:'',roles:['ADMIN'],permissions:['system:user:manage','system:role:manage']};
 vi.spyOn(session,'reload').mockResolvedValue();
 const wrapper=mount(component,{global:{plugins:[pinia],stubs:{ElDialog:{props:['modelValue'],template:'<section v-if="modelValue" role="dialog"><slot/><slot name="footer"/></section>'}}}});
 wrappers.push(wrapper);return {wrapper,session};
}
it('opens the current applicant full record and clears details on account change',async()=>{
 vi.spyOn(api.http,'get').mockResolvedValue(envelope([application()]));
 const {wrapper,session}=setup(ApplicationsView);await flushPromises();
 await wrapper.get('button[aria-label="查看申请68详情"]').trigger('click');
 expect(wrapper.get('[role="dialog"]').text()).toContain('demo_admin');
 expect(wrapper.get('[role="dialog"]').text()).toContain('完整申请说明');
 expect(wrapper.get('[role="dialog"]').text()).toContain('68');
 session.user=null;await flushPromises();expect(wrapper.find('[role="dialog"]').exists()).toBe(false);
});
it('requests server-side processed/pending filters with page reset and ignores late results',async()=>{
 let resolveOld!:(value:ReturnType<typeof envelope>)=>void;
 const get=vi.spyOn(api.http,'get').mockResolvedValueOnce(envelope([application()],45))
  .mockImplementationOnce(()=>new Promise(resolve=>{resolveOld=resolve;}))
  .mockResolvedValueOnce(envelope([application('94','APPROVED')]));
 const {wrapper}=setup(AdminApplicationsView);await flushPromises();
 wrapper.getComponent({name:'ElPagination'}).vm.$emit('update:current-page',2);
 wrapper.getComponent({name:'ElPagination'}).vm.$emit('current-change',2);await flushPromises();
 const processed=wrapper.findAll('button').find(b=>b.text()==='已处理')!;await processed.trigger('click');await flushPromises();
 expect(get).toHaveBeenLastCalledWith('/admin/role-applications',{params:{page:1,status:'PROCESSED'}});
 resolveOld(envelope([application('old')],45));await flushPromises();
 expect(wrapper.find('button[aria-label="查看申请old详情"]').exists()).toBe(false);
 await wrapper.get('button[aria-label="查看申请94详情"]').trigger('click');
 expect(wrapper.get('[role="dialog"]').text()).toContain('审核意见全文');
 get.mockResolvedValueOnce(envelope([application()]));
 await wrapper.findAll('button').find(b=>b.text()==='待处理')!.trigger('click');await flushPromises();
 expect(get).toHaveBeenLastCalledWith('/admin/role-applications',{params:{page:1,status:'PENDING'}});
});
it('returns to a valid page after the last pending record on a page has been processed',async()=>{
 const get=vi.spyOn(api.http,'get').mockResolvedValueOnce(envelope([application()],21))
  .mockResolvedValueOnce(envelope([],20)).mockResolvedValueOnce(envelope([application('first')],20));
 const {wrapper}=setup(AdminApplicationsView);await flushPromises();
 const pagination=wrapper.getComponent({name:'ElPagination'});
 pagination.vm.$emit('update:current-page',2);pagination.vm.$emit('current-change',2);await flushPromises();
 expect(get).toHaveBeenLastCalledWith('/admin/role-applications',{params:{page:1,status:'ALL'}});
 expect(wrapper.find('button[aria-label="查看申请first详情"]').exists()).toBe(true);
});
it('keeps own application readable but disables review in both list and detail',async()=>{
 vi.spyOn(api.http,'get').mockResolvedValue(envelope([application()]));
 const patch=vi.spyOn(api.http,'patch');
 const {wrapper}=setup(AdminApplicationsView);await flushPromises();
 const process=wrapper.findAll('button').find(b=>b.text()==='处理申请')!;
 expect(process.attributes('disabled')).toBeDefined();
 expect(wrapper.text()).toContain('本人申请，需其他管理员处理');
 await process.trigger('click');expect(wrapper.find('[role="dialog"]').exists()).toBe(false);
 await wrapper.get('button[aria-label="查看申请68详情"]').trigger('click');
 const detail=wrapper.get('[role="dialog"]');expect(detail.text()).toContain('本人申请，需其他管理员处理');
 expect(detail.findAll('button').some(b=>b.text()==='处理申请')).toBe(false);
 expect(patch).not.toHaveBeenCalled();
});
it('shows failed application decisions inside the open form and preserves the note',async()=>{
 vi.spyOn(api.http,'get').mockResolvedValue(envelope([{...application(),userId:'2377',username:'applicant'}]));
 vi.spyOn(api.http,'patch').mockRejectedValue(new Error('申请已处理，请刷新列表'));
 const {wrapper}=setup(AdminApplicationsView);await flushPromises();
 await wrapper.findAll('button').find(b=>b.text()==='处理申请')!.trigger('click');
 let form=wrapper.get('[role="dialog"]');await form.get('textarea').setValue('保留这份处理意见');
 await form.findAll('button').find(b=>b.text()==='通过申请')!.trigger('click');await flushPromises();
 form=wrapper.get('[role="dialog"]');expect(form.get('[role="alert"]').text()).toContain('申请已处理');
 expect((form.get('textarea').element as HTMLTextAreaElement).value).toBe('保留这份处理意见');
 expect(form.findAll('button').find(b=>b.text()==='通过申请')!.attributes('disabled')).toBeUndefined();
});
it('shows failed role updates in the users role dialog',async()=>{
 const other:User={id:'2377',username:'other_user',nickname:'其他用户',bio:null,status:'ACTIVE',roles:['LEARNER'],permissions:[],createdAt:''};
 vi.spyOn(api.http,'get').mockResolvedValue(envelope([other]));
 vi.spyOn(api.http,'put').mockRejectedValue(new Error('角色更新冲突，请刷新后重试'));
 const {wrapper}=setup(AdminUsersView);await flushPromises();
 await wrapper.get('.el-table__body-wrapper').findAll('button').find(b=>b.text()==='角色设置')!.trigger('click');
 const form=wrapper.get('[role="dialog"]');await form.findAll('button').find(b=>b.text()==='保存角色')!.trigger('click');await flushPromises();
 expect(form.get('[role="alert"]').text()).toContain('角色更新冲突');
 expect(form.text()).toContain('other_user');
});
