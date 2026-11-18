<script setup lang="ts">
import { onMounted, ref } from "vue";
import { api, errorMessage } from "../../shared/api/client";
import type { Envelope, Page, RoleApplication } from "../../shared/api/types";
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
const labels: Record<string, string> = {
  PENDING: "待处理",
  APPROVED: "已通过",
  REJECTED: "未通过",
  WITHDRAWN: "已撤回",
};
async function load() {
  loading.value = true;
  error.value = "";
  try {
    const { data } = await api.http.get<Envelope<Page<RoleApplication>>>(
      "/admin/role-applications",
      { params: { page: page.value } },
    );
    items.value = data.data.items;
    total.value = data.data.total;
  } catch (e) {
    error.value = errorMessage(e);
  } finally {
    loading.value = false;
  }
}
onMounted(load);
function select(item: RoleApplication) {
  selected.value = item;
  note.value = "";
  dialog.value = true;
}
async function decide(decision: "APPROVED" | "REJECTED") {
  if (!note.value.trim() || !selected.value) return;
  busy.value = true;
  error.value = "";
  success.value = "";
  try {
    await api.http.patch(`/admin/role-applications/${selected.value.id}`, {
      decision,
      note: note.value,
    });
    dialog.value = false;
    await load();
    success.value = "申请处理结果已保存";
  } catch (e) {
    error.value = errorMessage(e);
  } finally {
    busy.value = false;
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
  <p v-if="error" class="error-message" role="alert">{{ error }}</p>
  <p v-if="success" class="success-message" role="status">{{ success }}</p>
  <el-skeleton v-if="loading" :rows="5" animated /><el-table
    v-else
    :data="items"
    empty-text="暂无资格申请"
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
        labels[row.status]
      }}</template></el-table-column
    ><el-table-column
      prop="reviewNote"
      label="处理意见"
      min-width="160"
    /><el-table-column label="操作" width="110"
      ><template #default="{ row }"
        ><el-button
          v-if="row.status === 'PENDING'"
          link
          type="primary"
          @click="select(row as RoleApplication)"
          >处理申请</el-button
        ><span v-else class="muted">已处理</span></template
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
      <strong>{{ selected?.username }}</strong>
    </p>
    <p class="preserve-whitespace">{{ selected?.statement }}</p>
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
</template>
