<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { stageApi, stateLabels, type Review } from "./api";
import { useSessionStore } from "../../shared/stores/session";
import { useWorkspace } from "./useWorkspace";
import WorkspaceState from "./WorkspaceState.vue";
import PaginationBar from "./PaginationBar.vue";
import FeedbackQueue from "./FeedbackQueue.vue";
import "./stage.css";
import StatusBadge from "../../app/ui/StatusBadge.vue";
const session = useSessionStore();
const professional = computed(
  () => !!session.user?.permissions.includes("knowledge:review"),
);
const route = useRoute(),
  router = useRouter(),
  { loading, busy, error, actionError, message, load, act } = useWorkspace();
const items = ref<Review[]>([]),
  total = ref(0),
  status = ref("PENDING"),
  page = ref(1);
function refresh() {
  status.value = String(route.query.status || "PENDING");
  page.value = Math.max(1, Number(route.query.page) || 1);
  load(
    () => stageApi.reviews({ status: status.value, page: page.value }),
    (value) => {
      items.value = value.items;
      total.value = value.total;
    },
  );
}
function search(next = 1) {
  router.push({
    path: "/reviews",
    query: { status: status.value, page: next > 1 ? next : undefined },
  });
}
watch(() => route.fullPath, refresh, { immediate: true });
</script>
<template>
  <section class="stage-page">
    <header class="stage-heading">
      <div>
        <h1>内容审核</h1>
        <p>核对来源与知识事实，审核结论会保留意见和版本记录。</p>
      </div>
      <RouterLink to="/knowledge">返回知识中心</RouterLink>
    </header>
    <form class="stage-toolbar" @submit.prevent="search()">
      <label class="stage-field"
        >任务状态<select v-model="status">
          <option value="PENDING">待审核</option>
          <option value="APPROVED">已通过</option>
          <option value="REJECTED">已退回</option>
          <option value="WITHDRAWN">已撤回</option>
          <option value="ALL">全部状态</option>
        </select></label
      ><el-button native-type="submit">筛选任务</el-button>
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
              item.kind === "SOURCE"
                ? "来源"
                : item.kind === "ARTICLE"
                  ? "文章"
                  : "知识"
            }}</span><StatusBadge :state="item.status" :label="stateLabels[item.status] || item.status" />
            <span>{{ item.submitterName }}</span
            ><span v-if="item.reviewerId">已领取</span>
          </div>
          <h2>
            <RouterLink :to="`/reviews/${item.id}`">{{
              item.title
            }}</RouterLink>
          </h2>
          <p v-if="item.reviewNote" class="muted">{{ item.reviewNote }}</p>
        </li>
      </ul>
      <div v-else class="stage-empty">
        <h2>没有符合条件的审核任务</h2>
        <p>切换状态查看已处理任务，或稍后刷新。本人提交的内容不能自行审核。</p>
      </div>
      <PaginationBar :page="page" :total="total" @change="search" />
    </WorkspaceState>
    <details v-if="professional" class="stage-details">
      <summary>知识纠错处理</summary>
      <FeedbackQueue />
    </details>
  </section>
</template>
