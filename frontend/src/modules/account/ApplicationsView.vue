<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { api, errorMessage } from "../../shared/api/client";
import type { Envelope, Page, RoleApplication } from "../../shared/api/types";
import { useSessionStore } from "../../shared/stores/session";
const session = useSessionStore();
const items = ref<RoleApplication[]>([]),
  statement = ref(""),
  error = ref(""),
  success = ref(""),
  busy = ref(false),
  loading = ref(true),
  page = ref(1),
  total = ref(0);
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
  loading.value = true;
  error.value = "";
  try {
    const { data } = await api.http.get<Envelope<Page<RoleApplication>>>(
      "/me/role-applications",
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
async function submit() {
  busy.value = true;
  error.value = "";
  success.value = "";
  try {
    await api.http.post("/me/role-applications", {
      roleCode: "CONTRIBUTOR",
      statement: statement.value,
    });
    statement.value = "";
    page.value = 1;
    await load();
    success.value = "申请已提交，请等待管理员处理";
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
      <h1>成为内容贡献者</h1>
      <p class="muted">让你掌握的知识，成为更多人可靠的学习起点。</p>
    </div>
  </div>
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
      <h2>申请记录</h2>
      <el-skeleton v-if="loading" :rows="3" animated />
      <p v-else-if="!items.length" class="muted">
        还没有申请记录。提交后，你可以在这里查看处理结果。
      </p>
      <ul v-else class="history-list">
        <li v-for="item in items" :key="item.id">
          <div class="row-between">
            <strong>{{ labels[item.status] }}</strong
            ><time class="muted">{{
              new Date(item.createdAt).toLocaleDateString("zh-CN", {
                timeZone: "Asia/Shanghai",
              })
            }}</time>
          </div>
          <p>{{ item.statement }}</p>
          <p v-if="item.reviewNote" class="muted">
            处理意见：{{ item.reviewNote }}
          </p>
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
</template>
