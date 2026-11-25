import {ref,watch} from 'vue';
import {defineStore} from 'pinia';
import {useSessionStore} from '../../shared/stores/session';
import type {KnowledgeSummary} from './api';
export const useFormulaShelf=defineStore('formula-shelf',()=>{
 const session=useSessionStore();
 const selected=ref<Pick<KnowledgeSummary,'id'|'revisionId'|'title'|'canonicalName'|'versionNo'>[]>([]);
 const notice=ref('');
 function clear(){selected.value=[];notice.value='';}
 watch(()=>session.user?.id,clear,{flush:'sync'});
 function includes(id:string){return selected.value.some(item=>item.id===id);}
 function add(item:KnowledgeSummary){
  if(item.type!=='FORMULA')return 'wrongType';
  if(includes(item.id))return 'duplicate';
  if(selected.value.length>=3){notice.value='最多选择3个方剂，移除后可替换。';return 'full';}
  const {id,revisionId,title,canonicalName,versionNo}=item;
  selected.value.push({id,revisionId,title,canonicalName,versionNo});notice.value='已加入比较：'+canonicalName;return 'added';
 }
 function remove(id:string){selected.value=selected.value.filter(item=>item.id!==id);notice.value='已移除，可继续选择方剂。';}
 return {selected,notice,includes,add,remove,clear};
});
