<script setup lang="ts">
import {computed,onBeforeUnmount,ref} from 'vue';
import {post,type SourceDocument} from './api';
import {errorMessage} from '../../shared/api/client';
const props=defineProps<{item:SourceDocument}>();
const emit=defineEmits<{changed:[]}>();
const open=ref(false),busy=ref(false),reason=ref(''),error=ref(''),message=ref('');
const keys=['publicReadingAllowed','publicDownloadAllowed','quoteDisplayAllowed','ragUseAllowed'] as const;
const labels=['全文阅读','原件下载','引文展示','RAG使用'];
const values=ref<Record<(typeof keys)[number],boolean>>({publicReadingAllowed:false,publicDownloadAllowed:false,quoteDisplayAllowed:false,ragUseAllowed:false});
const publicImpact=computed(()=>!!props.item.rights?.quoteDisplayAllowed&&!values.value.quoteDisplayAllowed||props.item.rightsStatus==='LEGACY_UNCONFIRMED'&&!values.value.quoteDisplayAllowed);
let alive=true;onBeforeUnmount(()=>{alive=false;});
function begin(){for(const key of keys)values.value[key]=!!props.item.rights?.[key];reason.value='';error.value='';message.value='';open.value=true;}
async function save(){
  if(!reason.value.trim()||reason.value.length>1000){error.value='请填写1000字以内的授权变更原因';return;}
  if(values.value.ragUseAllowed&&!values.value.quoteDisplayAllowed){error.value='收回引文展示时，请同时收回RAG使用';return;}
  const document=props.item.id,version=props.item.lockVersion;
  busy.value=true;error.value='';
  try{await post(`/source-documents/${document}/rights/restrict`,{expectedVersion:version,...values.value,reason:reason.value.trim()});if(!alive||props.item.id!==document)return;open.value=false;message.value='有效授权已更新，历史审核快照已保留';emit('changed');}
  catch(e){if(alive&&props.item.id===document)error.value=errorMessage(e);}
  finally{if(alive)busy.value=false;}
}
</script>
<template>
  <div class="source-rights-action">
    <button type="button" @click="begin">收回授权范围</button>
    <p v-if="message" role="status">{{message}}</p>
    <el-dialog v-model="open" title="收回来源授权" width="min(520px, calc(100vw - 32px))" :close-on-click-modal="!busy" :close-on-press-escape="!busy" :show-close="!busy">
      <p>取消勾选需要收回的范围。扩大授权需复制修订并重新审核。</p>
      <label v-for="(key,index) in keys" :key="key" style="display:block;margin:12px 0">
        <input v-model="values[key]" type="checkbox" :disabled="busy||!item.rights?.[key]" /> {{labels[index]}}
      </label>
      <p v-if="publicImpact" class="stage-file-note">收回引文展示后，依赖该来源的知识将暂停公开，历史正文及引用快照会保留。</p>
      <label style="display:block">变更原因<textarea v-model="reason" rows="3" maxlength="1000" :disabled="busy" style="display:block;width:100%;margin-top:8px" /></label>
      <p v-if="error" role="alert" class="error-message">{{error}}</p>
      <template #footer><button type="button" :disabled="busy" @click="open=false">取消</button><button type="button" class="primary-link" :disabled="busy" @click="save">{{busy?'正在保存…':'确认收回授权'}}</button></template>
    </el-dialog>
  </div>
</template>
