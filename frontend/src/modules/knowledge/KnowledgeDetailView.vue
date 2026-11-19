<script setup lang="ts">
import { ref, watch } from "vue";
import { useRoute } from "vue-router";
import { api } from "../../shared/api/client";
import { useSessionStore } from "../../shared/stores/session";
import { stageApi, post, typeLabels, type KnowledgeDetail } from "./api";
import { useWorkspace } from "./useWorkspace";
import KnowledgeRead from "./KnowledgeRead.vue";
import VersionCompare from "./VersionCompare.vue";
import WorkspaceState from "./WorkspaceState.vue";
import "./stage.css";
const route = useRoute(),
  session = useSessionStore(),
  { loading, busy, error, actionError, message, load, act } = useWorkspace();
const item = ref<KnowledgeDetail | null>(null),
  feedback = ref(""),
  bookmarked = ref<boolean | null>(null),
  reason = ref("");
function refresh() {
  bookmarked.value = null;
  item.value = null;
  load(
    () => stageApi.detail(String(route.params.id)),
    (result) => {
      item.value = result;
      bookmarked.value =
        typeof result.bookmarked === "boolean" ? result.bookmarked : null;
    },
  );
}
function bookmark() {
  if (!item.value) return;
  const id = item.value.id,
    active = bookmarked.value === true;
  act(
    () =>
      active
        ? api.http.delete(`/resources/${id}/bookmark`)
        : api.http.put(`/resources/${id}/bookmark`),
    () => {
      bookmarked.value = !active;
    },
    active ? "已取消收藏" : "已收藏，可在个人中心查看",
  );
}
function sendFeedback() {
  if (!item.value || !feedback.value.trim()) return;
  act(
    () =>
      post(`/resources/${item.value!.id}/feedback`, {
        content: feedback.value.trim(),
      }),
    () => {
      feedback.value = "";
    },
    "纠错已提交，可在贡献工作台查看处理结果",
  );
}
function suspend() {
  if (!item.value || !reason.value.trim()) return;
  act(
    () =>
      post(`/knowledge/${item.value!.id}/suspend`, {
        reason: reason.value.trim(),
      }),
    () => refresh(),
    "知识已停用",
  );
}
watch(() => route.params.id, refresh, { immediate: true });
</script>
<template>
  <section class="stage-page">
    <RouterLink class="back-link" to="/knowledge">返回知识中心</RouterLink
    ><WorkspaceState
      :loading="loading"
      :error="error"
      :action-error="actionError"
      :message="message"
      @retry="refresh"
      ><template v-if="item"
        ><header class="stage-heading">
          <div>
            <div class="stage-meta">
              <span class="stage-tag">{{ typeLabels[item.type] }}</span><span
                >第 {{ item.versionNo }} 版 · 已审核</span
              >
            </div>
            <h1>{{ item.title }}</h1>
            <p>规范名称：{{ item.canonicalName }}</p>
          </div>
          <el-button
            :loading="busy"
            :aria-pressed="bookmarked === true"
            @click="bookmark"
            >{{ bookmarked ? "已收藏" : "收藏知识" }}</el-button
          >
        </header>
        <KnowledgeRead :item="item" /><VersionCompare :resource-id="item.id" />
        <details class="stage-details">
          <summary>发现内容问题？提交纠错</summary>
          <form @submit.prevent="sendFeedback">
            <label class="stage-field"
              >纠错内容<textarea
                v-model="feedback"
                required
                maxlength="5000"
                rows="4"
                placeholder="说明具体位置、问题和可核对的来源"
              ></textarea></label
            ><el-button
              type="primary"
              native-type="submit"
              :disabled="!feedback.trim()"
              :loading="busy"
              >提交纠错</el-button
            >
          </form>
        </details>
        <details
          v-if="session.user?.permissions.includes('knowledge:review')"
          class="stage-details"
        >
          <summary>审核操作：停用知识</summary>
          <p>停用后不再进入公开查询；恢复需要新版本审核。</p>
          <form @submit.prevent="suspend">
            <label class="stage-field"
              >停用原因<textarea
                v-model="reason"
                required
                rows="3"
                maxlength="1000"
              ></textarea></label
            ><el-button
              native-type="submit"
              type="danger"
              :loading="busy"
              :disabled="!reason.trim()"
              >停用知识</el-button
            >
          </form>
        </details>
        <p class="stage-hint">
          内容用于中医知识学习，不替代专业诊疗。方剂用量为来源记载，不作为个人用药建议。
        </p></template
      ></WorkspaceState
    >
  </section>
</template>
