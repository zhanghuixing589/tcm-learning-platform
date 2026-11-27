<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref, watch } from "vue";
import { api, errorMessage } from "../../shared/api/client";
import type { Envelope, Page, RoleApplication } from "../../shared/api/types";
import { useSessionStore } from "../../shared/stores/session";
import StatusBadge from "../../app/ui/StatusBadge.vue";
import RoleApplicationDetails from './RoleApplicationDetails.vue';
const session = useSessionStore();
const items = ref<RoleApplication[]>([]),
  statement = ref(""),
  error = ref(""),
  success = ref(""),
  busy = ref(false),
  loading = ref(true),
  page = ref(1),
  total = ref(0);
const details=ref<RoleApplication|null>(null),detailOpen=ref(false);
let loadSequence=0;
watch(()=>session.user?.id,()=>{++loadSequence;items.value=[];total.value=0;details.value=null;detailOpen.value=false;statement.value='';busy.value=false;error.value='';success.value='';loading.value=false;});
onBeforeUnmount(()=>{++loadSequence;});
const blocked = computed(
  () =>
    session.user?.roles.includes("CONTRIBUTOR") ||
    items.value.some((i) => i.status === "PENDING"),
);
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
    const [{ data }] = await Promise.all([api.http.get<Envelope<Page<RoleApplication>>>(
      "/me/role-applications",
      { params: { page: page.value } },
    ),session.reload()]);
    if(sequence!==loadSequence || actor!==session.user?.id)return;
    items.value = data.data.items;
    total.value = data.data.total;
  } catch (e) {
    if(sequence===loadSequence && actor===session.user?.id)error.value = errorMessage(e);
  } finally {
    if(sequence===loadSequence)loading.value = false;
  }
}
onMounted(load);
async function submit() {
  const actor=session.user?.id;
  busy.value = true;
  error.value = "";
  success.value = "";
  try {
    await api.http.post("/me/role-applications", {
      roleCode: "CONTRIBUTOR",
      statement: statement.value,
    });
    if(actor!==session.user?.id)return;
    statement.value = "";
    page.value = 1;
    await load();
    if(actor===session.user?.id)success.value = "申请已提交，请等待管理员处理";
  } catch (e) {
    if(actor===session.user?.id)error.value = errorMessage(e);
  } finally {
    if(actor===session.user?.id)busy.value = false;
  }
}
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>成为内容贡献者</h1>
      <p class="muted">让你掌握的知识，成为更多人可靠的学习起点。</p>
    </div>
  </div>
  <ol class="application-steps" aria-label="申请流程"><li><span>01</span><strong>介绍你的背景</strong><small>说明学习经历与资料来源</small></li><li><span>02</span><strong>等待资格审核</strong><small>在申请记录中查看处理意见</small></li><li><span>03</span><strong>开始知识共建</strong><small>通过后前往贡献工作台</small></li></ol>
  <p v-if="error" class="error-message" role="alert">{{ error }}</p>
  <p v-if="success" class="success-message" role="status">{{ success }}</p>
  <div class="account-grid">
    <section class="panel">
      <h2>贡献资格申请</h2>
      <p v-if="blocked" class="muted">
        {{
          session.user?.roles.includes("CONTRIBUTOR")
            ? "你已具备贡献资格。"
            : "你有一份待处理申请，请耐心等待审核。"
        }}
      </p>
      <el-form
        v-else
        @submit.prevent="submit"
        label-position="top"
        :disabled="busy || loading"
        ><el-form-item label="申请说明" for="application-statement"
          ><el-input
            id="application-statement"
            v-model="statement"
            type="textarea"
            :rows="6"
            required
            maxlength="2000"
            show-word-limit
            placeholder="介绍你的学习背景、资料来源，以及希望贡献的内容" /></el-form-item
        ><el-button type="primary" native-type="submit" :loading="busy"
          >提交申请</el-button
        ></el-form
      >
    </section>
    <section class="account-aside">
      <div class="row-between"><h2>申请记录</h2><el-button :loading="loading" @click="load">刷新记录</el-button></div>
      <el-skeleton v-if="loading" :rows="3" animated />
      <p v-else-if="!items.length" class="muted">
        还没有申请记录。提交后，你可以在这里查看处理结果。
      </p>
      <ul v-else class="history-list">
        <li v-for="item in items" :key="item.id">
          <div class="row-between">
            <StatusBadge :state="item.status" :label="labels[item.status] || item.status" />
            <time class="muted">{{
              new Date(item.createdAt).toLocaleDateString("zh-CN", {
                timeZone: "Asia/Shanghai",
              })
            }}</time>
          </div>
          <p class="muted">申请 {{ item.id }} · {{ item.username }}</p>
          <p>{{ item.statement }}</p>
          <p v-if="item.reviewNote" class="muted">
            处理意见：{{ item.reviewNote }}
          </p>
          <el-button link type="primary" :aria-label="`查看申请${item.id}详情`" @click="details=item;detailOpen=true">查看详情</el-button>
        </li>
      </ul>
      <el-pagination
        v-if="total > 20"
        v-model:current-page="page"
        layout="prev, next"
        :total="total"
        :page-size="20"
        @current-change="load"
      />
    </section>
  </div>
  <RoleApplicationDetails v-model="detailOpen" :application="details"/>
</template>
