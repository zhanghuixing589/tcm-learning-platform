<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import type { Page } from "../../shared/api/types";
import { formatDate, latestRequest } from "../account/personalCenter";
import { communityApi } from "./api";
import { characterCount, communityError, idempotencyKeys, quotaLabels } from "./editor";
import type { Quota, QuotaRequest } from "./types";
import CommunityPageBar from "./CommunityPageBar.vue";

const props = defineProps<{ articleId: string; quota: Quota; canRequest: boolean; bodyCount: number }>();
const emit = defineEmits<{ quota: [value: Quota, refreshed: boolean] }>();
const history = ref<Page<QuotaRequest> | null>(null);
const historyOpen = ref(false);
const historyLoading = ref(false);
const requestedLimit = ref<number | "">("");
const reason = ref("");
const busy = ref(false);
const error = ref("");
const historyError = ref("");
const note = ref("");
const historyRequest = latestRequest();
const keys = idempotencyKeys();
let generation = 0;
const allowRequest = computed(() => props.canRequest && props.quota.latestRequest?.status !== "PENDING"
  && props.quota.bodyCharLimit < props.quota.bodyHardLimit);
const suggested = computed(() => Math.min(props.quota.bodyHardLimit,
  Math.max(props.bodyCount, props.quota.bodyCharLimit + 1)));

async function loadHistory(page = 1) {
  historyLoading.value = true;
  historyError.value = "";
  const ticket = historyRequest.begin();
  try {
    const result = await communityApi.quotaHistory(props.articleId, page);
    if (historyRequest.isCurrent(ticket)) history.value = result;
  } catch (cause) {
    if (historyRequest.isCurrent(ticket)) historyError.value = communityError(cause);
  } finally {
    if (historyRequest.isCurrent(ticket)) historyLoading.value = false;
  }
}
watch(historyOpen, (open) => { if (open && !history.value) void loadHistory(); });
async function refresh() {
  if (busy.value) return;
  const current = generation;
  busy.value = true;
  error.value = "";
  note.value = "";
  try {
    const result = await communityApi.quota(props.articleId);
    if (current !== generation) return;
    emit("quota", result, true);
    note.value = "已刷新正文额度与申请状态。";
    if (historyOpen.value) void loadHistory(history.value?.page || 1);
  } catch (cause) {
    if (current === generation) error.value = communityError(cause);
  } finally {
    if (current === generation) busy.value = false;
  }
}
async function request() {
  if (busy.value || !allowRequest.value) return;
  const current = generation;
  const limit = requestedLimit.value || suggested.value;
  if (!reason.value.trim() || characterCount(reason.value) > 2000 || !Number.isInteger(limit)
      || limit <= props.quota.bodyCharLimit || limit > props.quota.bodyHardLimit) {
    error.value = "请填写扩容原因，并输入高于当前额度且不超过系统上限的整数。";
    return;
  }
  const input = { requestedLimit: limit, reason: reason.value };
  busy.value = true;
  error.value = "";
  note.value = "";
  try {
    const result = await communityApi.requestQuota(props.articleId, input, keys.for("quota-request", input));
    if (current !== generation) return;
    keys.forget("quota-request");
    emit("quota", { ...props.quota, latestRequest: result }, false);
    requestedLimit.value = "";
    reason.value = "";
    note.value = "扩容申请已提交。";
    if (historyOpen.value) void loadHistory();
  } catch (cause) {
    if (current === generation) error.value = communityError(cause);
  } finally {
    if (current === generation) busy.value = false;
  }
}
onBeforeUnmount(() => { generation++; historyRequest.invalidate(); });
</script>

<template>
  <section class="community-panel" aria-label="正文额度与扩容">
    <h2>正文额度与扩容</h2>
    <p>当前额度 {{ quota.bodyCharLimit }} 字符，系统上限 {{ quota.bodyHardLimit }} 字符。</p>
    <p v-if="quota.latestRequest">
      最近申请{{ quotaLabels[quota.latestRequest.status] }}，申请 {{ quota.latestRequest.requestedLimit }} 字符
      <span v-if="quota.latestRequest.approvedLimit">，已批准 {{ quota.latestRequest.approvedLimit }} 字符</span>
    </p>
    <p v-if="quota.latestRequest?.reviewNote" class="community-literal">审批意见 {{ quota.latestRequest.reviewNote }}</p>
    <p v-if="error" role="alert" class="error-message">{{ error }}</p>
    <p v-if="note" role="status" class="success-message">{{ note }}</p>
    <div class="community-actions">
      <button type="button" data-refresh-quota :disabled="busy" @click="refresh">刷新额度</button>
      <button type="button" :aria-expanded="historyOpen" @click="historyOpen = !historyOpen">申请历史</button>
    </div>
    <form v-if="allowRequest" class="community-quota-form" @submit.prevent="request">
      <label class="community-field">申请额度
        <input type="number" step="1" :min="quota.bodyCharLimit + 1" :max="quota.bodyHardLimit"
          :value="requestedLimit || suggested" :disabled="busy"
          @input="requestedLimit = ($event.target as HTMLInputElement).valueAsNumber || ''" required />
      </label>
      <label class="community-field">扩容原因
        <textarea v-model="reason" rows="3" required :disabled="busy"></textarea>
      </label>
      <button type="submit" :disabled="busy">提交扩容申请</button>
    </form>
    <section v-if="historyOpen" aria-label="申请记录">
      <p v-if="historyLoading" role="status">正在读取申请记录…</p>
      <div v-if="historyError">
        <p role="alert" class="error-message">{{ historyError }}</p>
        <button type="button" @click="loadHistory(history?.page || 1)">重新加载申请历史</button>
      </div>
      <template v-if="history">
        <p v-if="!history.items.length" class="community-empty">暂无扩容申请记录。</p>
        <ul class="community-rows">
          <li v-for="item in history.items" :key="item.id" class="community-row">
            <strong>{{ quotaLabels[item.status] }}</strong>，申请 {{ item.requestedLimit }} 字符
            <span v-if="item.approvedLimit">，批准 {{ item.approvedLimit }} 字符</span>
            <time class="muted" :datetime="item.createdAt">{{ formatDate(item.createdAt) }}</time>
            <p class="community-literal">{{ item.reason }}</p>
            <p v-if="item.reviewNote" class="community-literal">审批意见 {{ item.reviewNote }}</p>
          </li>
        </ul>
        <CommunityPageBar :result="history" :disabled="historyLoading" label="额度申请分页" @change="loadHistory" />
      </template>
    </section>
  </section>
</template>
