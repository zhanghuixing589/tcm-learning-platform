<script setup lang="ts">
import { computed, nextTick, onMounted, onBeforeUnmount, ref, watch } from "vue";
import { api, errorMessage } from "../../shared/api/client";
import type { Envelope, Page, RoleApplication } from "../../shared/api/types";
import {useSessionStore} from '../../shared/stores/session';
import RoleApplicationDetails from '../account/RoleApplicationDetails.vue';
const session=useSessionStore();
type Filter='ALL'|'PENDING'|'PROCESSED';
const filter=ref<Filter>('ALL');
const filters:{value:Filter;label:string}[]=[{value:'ALL',label:'全部'},{value:'PENDING',label:'待处理'},{value:'PROCESSED',label:'已处理'}];
const detail=ref<RoleApplication|null>(null),detailOpen=ref(false);
let loadSequence=0;
function changeFilter(value:Filter){filter.value=value;page.value=1;detailOpen.value=false;void load();}
const items = ref<RoleApplication[]>([]),
  loading = ref(true),
  busy = ref(false),
  error = ref(""),
  success = ref(""),
  page = ref(1),
  total = ref(0);
const selected = ref<RoleApplication | null>(null),
  note = ref(""),
  dialog = ref(false);
const decisionError=ref(''),decisionErrorElement=ref<HTMLElement|null>(null);
const selectedUsername=computed(()=>selected.value?.username ?? '');
const selectedStatement=computed(()=>selected.value?.statement ?? '');
function isOwnApplication(item:RoleApplication){return item.userId===session.user?.id;}
function isPendingApplication(item:RoleApplication){return item.status==='PENDING';}
function canProcess(item:RoleApplication){return !!session.user?.id && isPendingApplication(item) && !isOwnApplication(item);}
function applicationStatus(item:RoleApplication){return labels[item.status] || item.status;}
async function showDecisionError(message:string){
  decisionError.value=message;await nextTick();
  decisionErrorElement.value?.scrollIntoView?.({block:'nearest'});
  decisionErrorElement.value?.focus({preventScroll:true});
}
watch(()=>session.user?.id,()=>{++loadSequence;items.value=[];total.value=0;selected.value=null;detail.value=null;dialog.value=false;detailOpen.value=false;note.value='';decisionError.value='';busy.value=false;error.value='';success.value='';loading.value=false;});
onBeforeUnmount(()=>{++loadSequence;});
const labels: Record<string, string> = {
  PENDING: "待处理",
  APPROVED: "已通过",
  REJECTED: "未通过",
  WITHDRAWN: "已撤回",
};
async function load() {
  const sequence=++loadSequence,actor=session.user?.id;
  loading.value = true;
  error.value = "";
  try {
    const { data } = await api.http.get<Envelope<Page<RoleApplication>>>(
      "/admin/role-applications",
      { params: { page: page.value, status: filter.value } },
    );
    if(sequence!==loadSequence || actor!==session.user?.id)return;
    total.value = data.data.total;
    const lastPage=Math.max(1,Math.ceil(total.value/20));
    if(page.value>lastPage){page.value=lastPage;await load();return;}
    items.value = data.data.items;
  } catch (e) {
    if(sequence===loadSequence && actor===session.user?.id)error.value = errorMessage(e);
  } finally {
    if(sequence===loadSequence)loading.value = false;
  }
}
onMounted(load);
function select(item: RoleApplication) {
  if(!canProcess(item)){
    error.value=isOwnApplication(item)?'本人申请，需其他管理员处理':'申请已处理，请刷新列表';
    return;
  }
  detailOpen.value=false;
  error.value='';decisionError.value='';
  selected.value = item;
  note.value = "";
  dialog.value = true;
}
async function decide(decision: "APPROVED" | "REJECTED") {
  if (!note.value.trim() || !selected.value) return;
  if(!canProcess(selected.value)){
    await showDecisionError(isOwnApplication(selected.value)?'不能审核自己的申请，请由其他管理员处理。':'申请已处理，请刷新列表');
    return;
  }
  const actor=session.user?.id;
  busy.value = true;
  error.value = "";
  decisionError.value='';
  success.value = "";
  try {
    await api.http.patch(`/admin/role-applications/${selected.value.id}`, {
      decision,
      note: note.value,
    });
    if(actor!==session.user?.id)return;
    dialog.value = false;
    await load();
    if(actor===session.user?.id)success.value = "申请处理结果已保存";
  } catch (e) {
    if(actor===session.user?.id)await showDecisionError(errorMessage(e));
  } finally {
    if(actor===session.user?.id)busy.value = false;
  }
}
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>贡献资格申请</h1>
      <p class="muted">核对申请说明，再授予内容贡献资格。</p>
    </div>
    <el-button :loading="loading" @click="load">刷新列表</el-button>
  </div>
  <div class="application-filters" role="group" aria-label="申请状态筛选"><el-button v-for="option in filters" :key="option.value" :type="filter===option.value?'primary':undefined" :aria-pressed="filter===option.value" @click="changeFilter(option.value)">{{ option.label }}</el-button></div>
  <p v-if="error" class="error-message" role="alert">{{ error }}</p>
  <p v-if="success" class="success-message" role="status">{{ success }}</p>
  <el-skeleton v-if="loading" :rows="5" animated /><el-table
    v-else
    :data="items"
    :empty-text="filter==='PENDING'?'暂无待处理申请':filter==='PROCESSED'?'暂无已处理申请':'暂无资格申请'"
    class="data-table"
    ><el-table-column
      prop="username"
      label="申请人"
      min-width="120"
    /><el-table-column
      prop="statement"
      label="申请说明"
      min-width="240"
      show-overflow-tooltip
    /><el-table-column label="状态" width="100"
      ><template #default="{ row }">{{
        applicationStatus(row as RoleApplication)
      }}</template></el-table-column
    ><el-table-column
      prop="reviewNote"
      label="处理意见"
      min-width="160"
    /><el-table-column label="操作" width="210"
      ><template #default="{ row }"
        ><el-button link type="primary" :aria-label="`查看申请${row.id}详情`" @click="detail=row as RoleApplication;detailOpen=true">查看详情</el-button><el-button
          v-if="isPendingApplication(row as RoleApplication)"
          link
          type="primary"
          :disabled="!canProcess(row as RoleApplication) || busy"
          @click="select(row as RoleApplication)"
          >处理申请</el-button
        ><span v-if="isPendingApplication(row as RoleApplication) && isOwnApplication(row as RoleApplication)" class="self-application-hint">本人申请，需其他管理员处理</span></template
      ></el-table-column
    ></el-table
  >
  <el-pagination
    v-if="total > 20"
    v-model:current-page="page"
    layout="prev, pager, next"
    :total="total"
    :page-size="20"
    @current-change="load"
  />
  <el-dialog
    v-model="dialog"
    title="处理贡献资格申请"
    width="min(560px, 92vw)"
    :close-on-click-modal="!busy"
    :close-on-press-escape="!busy"
    :show-close="!busy"
    ><p>
      <strong>{{ selectedUsername }}</strong>
    </p>
    <p class="preserve-whitespace">{{ selectedStatement }}</p>
    <p v-if="decisionError" ref="decisionErrorElement" class="error-message" role="alert" tabindex="-1">{{ decisionError }}</p>
    <el-form label-position="top"
      ><el-form-item label="处理意见（必填）" for="decision-note"
        ><el-input
          id="decision-note"
          v-model="note"
          type="textarea"
          :rows="4"
          maxlength="2000"
          :disabled="busy" /></el-form-item></el-form
    ><template #footer
      ><el-button :disabled="busy" @click="dialog = false">取消</el-button
      ><el-button :disabled="busy || !note.trim()" @click="decide('REJECTED')"
        >不通过</el-button
      ><el-button
        type="primary"
        :loading="busy"
        :disabled="!note.trim()"
        @click="decide('APPROVED')"
        >通过申请</el-button
      ></template
    ></el-dialog
  >
  <RoleApplicationDetails v-model="detailOpen" :application="detail"><template #actions><span v-if="detail && isPendingApplication(detail) && isOwnApplication(detail)" class="self-application-hint" role="status">本人申请，需其他管理员处理</span><el-button v-if="detail && canProcess(detail)" type="primary" @click="select(detail)">处理申请</el-button></template></RoleApplicationDetails>
</template>
<style scoped>
.application-filters{display:flex;flex-wrap:wrap;gap:8px;margin:0 0 24px;}
.application-filters :deep(.el-button + .el-button){margin-left:0;}
.self-application-hint{display:block;color:var(--text-muted,#59665c);font-size:14px;line-height:1.6;margin-top:6px;}
</style>
