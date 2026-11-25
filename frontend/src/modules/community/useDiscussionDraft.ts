import { computed, ref, watch, type Ref } from 'vue';
import { useSessionStore } from '../../shared/stores/session';
export interface DiscussionDraft { title:string; body:string; sectionId:string; changeNote:string }
interface StoredDraft { version:1; data:DiscussionDraft; updatedAt:number }
export function discussionDraftKey(user:string,context:string){return 'tcm:community-draft:v1:'+encodeURIComponent(user)+':POST:'+encodeURIComponent(context||'new');}
export function parseDiscussionDraft(raw:string|null):StoredDraft|null {
 if(!raw)return null;
 const record=JSON.parse(raw);
 if(record?.version!==1 || !record.data || !['title','body','sectionId','changeNote'].every(key=>typeof record.data[key]==='string'))return null;
 const {title,body,sectionId,changeNote}=record.data;
 return {version:1,data:{title,body,sectionId,changeNote},updatedAt:Number(record.updatedAt)||0};
}
export function useDiscussionDraft(context:Readonly<Ref<string>>,ready:Readonly<Ref<boolean>>,form:DiscussionDraft,dirty:Readonly<Ref<boolean>>){
 const session=useSessionStore(),owner=session.user?.id;
 const recovery=ref<StoredDraft|null>(null),draftError=ref('');
 const draftState=ref<'empty'|'saved'|'failed'>('empty');
 const key=computed(()=>owner && session.user?.id===owner ? discussionDraftKey(owner,context.value):'');
 function discardDraft(){try{if(key.value)localStorage.removeItem(key.value);recovery.value=null;draftState.value='empty';draftError.value='';}catch{draftState.value='failed';draftError.value='浏览器存储不可用，请保留当前页面，或自行复制正文。';}}
 function restoreDraft(){if(recovery.value){Object.assign(form,recovery.value.data);recovery.value=null;draftState.value='saved';}}
 watch([key,ready],()=>{
  recovery.value=null;draftError.value='';draftState.value='empty';if(!key.value||!ready.value)return;
  try{const item=parseDiscussionDraft(localStorage.getItem(key.value));if(item && JSON.stringify(item.data)!==JSON.stringify({...form}))recovery.value=item;}
  catch{draftState.value='failed';draftError.value='无法读取本地草稿，浏览器可能禁用了存储。当前编辑不受影响。';}
 },{immediate:true,flush:'post'});
 watch(()=>[form.title,form.body,form.sectionId,form.changeNote,dirty.value],()=>{
  if(!key.value||!ready.value||recovery.value)return;
  try{if(dirty.value){localStorage.setItem(key.value,JSON.stringify({version:1,data:{title:form.title,body:form.body,sectionId:form.sectionId,changeNote:form.changeNote},updatedAt:Date.now()}));draftState.value='saved';}else{localStorage.removeItem(key.value);draftState.value='empty';}draftError.value='';}
  catch{draftState.value='failed';draftError.value='本地草稿未保存，浏览器存储已满或不可用。请复制正文后再离开。';}
 },{flush:'sync'});
 return {recovery,draftError,draftState,restoreDraft,discardDraft,clearDraft:discardDraft};
}
