<script setup lang="ts">
import type { RoleApplication } from '../../shared/api/types';
import StatusBadge from '../../app/ui/StatusBadge.vue';
defineProps<{modelValue:boolean; application:RoleApplication|null}>();
defineEmits<{ 'update:modelValue':[value:boolean] }>();
const labels:Record<string,string>={PENDING:'待处理',APPROVED:'已通过',REJECTED:'未通过',WITHDRAWN:'已撤回'};
function time(value:string|null){return value?new Date(value).toLocaleString('zh-CN',{timeZone:'Asia/Shanghai'}):'未记录';}
</script>
<template>
  <el-dialog :model-value="modelValue" title="资格申请详情" class="role-application-dialog" top="8vh" width="min(640px, 92vw)" @update:model-value="$emit('update:modelValue',$event)">
    <template v-if="application">
      <dl class="application-detail-meta">
        <div><dt>申请编号</dt><dd>{{ application.id }}</dd></div>
        <div><dt>申请人</dt><dd>{{ application.username }}</dd></div>
        <div><dt>申请资格</dt><dd>内容贡献者</dd></div>
        <div><dt>当前状态</dt><dd><StatusBadge :state="application.status" :label="labels[application.status] || application.status"/></dd></div>
        <div><dt>提交时间</dt><dd>{{ time(application.createdAt) }}</dd></div>
        <div><dt>处理时间</dt><dd>{{ !application.reviewedAt && application.status==='PENDING'?'尚未处理':time(application.reviewedAt) }}</dd></div>
      </dl>
      <h3>申请说明</h3><p class="application-detail-text">{{ application.statement }}</p>
      <h3>处理意见</h3><p class="application-detail-text">{{ application.reviewNote || (application.status==='PENDING'?'申请待处理，暂时没有处理意见。':'未填写处理意见。') }}</p>
    </template>
    <template #footer><el-button @click="$emit('update:modelValue',false)">关闭详情</el-button><slot name="actions"/></template>
  </el-dialog>
</template>
<style scoped>
:global(.role-application-dialog){display:flex;flex-direction:column;max-height:84dvh;}
:global(.role-application-dialog .el-dialog__body){overflow-y:auto;min-height:0;}
:global(.role-application-dialog .el-dialog__header),:global(.role-application-dialog .el-dialog__footer){flex-shrink:0;}
.application-detail-meta{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:20px;margin:0 0 28px;}
.application-detail-meta dt{color:var(--text-muted,#59665c);font-size:14px;margin-bottom:6px;}
.application-detail-meta dd{margin:0;overflow-wrap:anywhere;}
.application-detail-text{white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.8;margin:12px 0 24px;}
@media(max-width:480px){.application-detail-meta{grid-template-columns:1fr;gap:16px;}}
</style>
