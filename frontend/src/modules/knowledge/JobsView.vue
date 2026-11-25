<script setup lang="ts">
import { ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { stageApi, post, stateLabels, type Job } from "./api";
import { useWorkspace } from "./useWorkspace";
import WorkspaceState from "./WorkspaceState.vue";
import PaginationBar from "./PaginationBar.vue";
import "./stage.css";
import StatusBadge from "../../app/ui/StatusBadge.vue";
const route = useRoute(),
  router = useRouter(),
  { loading, busy, error, actionError, message, load, act } = useWorkspace();
const items = ref<Job[]>([]),
  total = ref(0),
  status = ref(""),
  jobType = ref(""),
  page = ref(1);
function refresh() {
  status.value = String(route.query.status || "");
  jobType.value = String(route.query.jobType || "");
  page.value = Math.max(1, Number(route.query.page) || 1);
  load(
    () =>
      stageApi.jobs({
        status: status.value,
        jobType: jobType.value,
        page: page.value,
      }),
    (value) => {
      items.value = value.items;
      total.value = value.total;
    },
  );
}
function search(next = 1) {
  router.push({
    path: "/admin/jobs",
    query: {
      status: status.value || undefined,
      jobType: jobType.value || undefined,
      page: next > 1 ? next : undefined,
    },
  });
}
function retry(item: Job) {
  act(
    () => post(`/admin/jobs/${item.id}/retries`),
    () => {
      item.status = "QUEUED";
    },
    "已重新排队",
  );
}
function expired(item: Job) {
  return (
    item.status === "RUNNING" &&
    !!item.leaseUntil &&
    Date.parse(item.leaseUntil) <= Date.now()
  );
}
watch(() => route.fullPath, refresh, { immediate: true });
</script>
<template>
  <section class="stage-page">
    <header>
      <h1>技术任务</h1>
      <p class="muted">
        查看解析任务和失败原因，重新排队失败或租约到期的任务。
      </p>
    </header>
    <p class="stage-file-note">
      知识索引任务等待第四阶段索引服务。当前记录表示待处理任务，不代表已完成向量或图谱索引。
    </p>
    <form class="stage-toolbar" @submit.prevent="search()">
      <label class="stage-field"
        >任务状态<select v-model="status">
          <option value="">全部状态</option>
          <option
            v-for="key in [
              'QUEUED',
              'RUNNING',
              'RETRY_WAIT',
              'SUCCEEDED',
              'DEAD',
              'CANCELLED',
            ]"
            :key="key"
            :value="key"
          >
            {{ stateLabels[key] }}
          </option>
        </select></label
      ><label class="stage-field"
        >任务类型<select v-model="jobType">
          <option value="">全部类型</option>
          <option value="PARSE">资料解析</option>
          <option value="VECTOR_INDEX">向量索引</option>
          <option value="GRAPH_SYNC">图谱同步</option>
          <option value="REMOVE_INDEX">移除索引</option>
        </select></label
      ><el-button native-type="submit">筛选</el-button
      ><el-button @click="refresh">刷新任务</el-button>
    </form>
    <WorkspaceState
      :loading="loading"
      :error="error"
      :action-error="actionError"
      :message="message"
      @retry="refresh"
      ><ul v-if="items.length" class="stage-list">
        <li v-for="item in items" :key="item.id">
          <div class="stage-meta">
            <span class="stage-tag">{{
              item.jobType === "PARSE" ? "资料解析" : "知识索引"
            }}</span><StatusBadge :state="item.status" :label="stateLabels[item.status] || item.status" /><span>任务 {{ item.id }}</span>
          </div>
          <p v-if="item.jobType !== 'PARSE'">等待第四阶段索引服务</p>
          <p v-if="item.lastError || item.errorMessage" class="error-message">
            {{ item.lastError || item.errorMessage }}
          </p>
          <p class="stage-hint">
            尝试次数：{{ item.attemptCount }} · 创建时间：{{ item.createdAt }}
          </p>
          <el-button
            v-if="
              ['DEAD', 'FAILED', 'RETRY_WAIT'].includes(item.status) ||
              expired(item)
            "
            :loading="busy"
            @click="retry(item)"
            >重试任务</el-button
          >
        </li>
      </ul>
      <div v-else class="stage-empty">
        <h2>没有符合条件的任务</h2>
        <p>调整筛选条件，或上传资料并发起解析。</p>
      </div>
      <PaginationBar :page="page" :total="total" @change="search"
    /></WorkspaceState>
  </section>
</template>
