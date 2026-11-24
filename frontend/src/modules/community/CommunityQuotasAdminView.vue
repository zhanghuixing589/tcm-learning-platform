<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from "vue";
import { useSessionStore } from "../../shared/stores/session";
import type { Page } from "../../shared/api/types";
import { formatDate, latestRequest } from "../account/personalCenter";
import { communityApi } from "./api";
import { characterCount, communityError, idempotencyKeys, quotaLabels } from "./editor";
import type { QuotaDecision, QuotaRequest, QuotaStatus } from "./types";
import CommunityPageBar from "./CommunityPageBar.vue";
import "./community.css";

const session = useSessionStore();
const status = ref<QuotaStatus | "ALL">("PENDING");
const page = ref<Page<QuotaRequest> | null>(null);
const loading = ref(false);
const busy = ref(false);
const error = ref("");
const actionError = ref("");
const message = ref("");
const selected = ref<QuotaRequest | null>(null);
const decision = ref<QuotaDecision["decision"]>("APPROVED");
const approvedLimit = ref<number | "">("");
const note = ref("");
const requests = latestRequest();
const keys = idempotencyKeys();
let epoch = 0;

async function load(number = 1) {
  const ticket = requests.begin();
  loading.value = true;
  error.value = "";
  try {
    const result = await communityApi.quotaQueue(status.value, number);
    if (requests.isCurrent(ticket)) page.value = result;
  } catch (cause) {
    if (requests.isCurrent(ticket)) error.value = communityError(cause);
  } finally {
    if (requests.isCurrent(ticket)) loading.value = false;
  }
}
function begin(item: QuotaRequest) {
  if (item.status !== "PENDING" || item.requestedBy === session.user?.id || busy.value) return;
  if (selected.value?.id === item.id && selected.value.lockVersion === item.lockVersion) return;
  selected.value = item;
  decision.value = "APPROVED";
  approvedLimit.value = item.requestedLimit;
  note.value = "";
  actionError.value = message.value = "";
}
async function save() {
  const item = selected.value;
  if (!item || item.requestedBy === session.user?.id || busy.value) return;
  const approve = decision.value === "APPROVED";
  const limit = Number(approvedLimit.value);
  actionError.value = message.value = "";
  if (!note.value.trim() || characterCount(note.value) > 2000 ||
      (approve && (!Number.isInteger(limit) || limit < 1 || limit > item.requestedLimit))) {
    actionError.value = "请填写审批意见，批准时输入不超过申请额度的正整数。";
    return;
  }
  const current = epoch;
  const input: QuotaDecision = { lockVersion: item.lockVersion, decision: decision.value,
    approvedLimit: approve ? limit : null, note: note.value };
  const operation = "decide:" + item.id;
  busy.value = true;
  try {
    const result = await communityApi.decideQuota(item.id, input, keys.for(operation, input));
    if (current !== epoch) return;
    keys.forget(operation);
    selected.value = null;
    message.value = "额度申请" + quotaLabels[result.status] + "。";
    await load(1);
  } catch (cause) {
    if (current === epoch) actionError.value = communityError(cause);
  } finally {
    if (current === epoch) busy.value = false;
  }
}
watch(status, () => {
  page.value = null;
  void load();
}, { immediate: true });
onBeforeUnmount(() => { epoch++; requests.invalidate(); });
</script>

<template>
  <div class="community-page">
    <header class="community-heading"><div><h1>正文扩容审批</h1><p>核对单篇文章的额度申请。</p></div></header>
    <div class="community-actions">
      <label class="community-field">申请状态
        <select v-model="status" :disabled="busy">
          <option value="ALL">全部状态</option>
          <option v-for="(label, value) in quotaLabels" :key="value" :value="value">{{ label }}</option>
        </select>
      </label>
      <button type="button" :disabled="loading || busy" @click="load(page?.page || 1)">刷新申请</button>
    </div>
    <p v-if="loading" role="status">正在读取额度申请…</p>
    <p v-if="error" role="alert" class="error-message">{{ error }}</p>
    <p v-if="message" role="status" class="success-message">{{ message }}</p>
    <template v-if="page">
      <p v-if="!page.items.length && !loading" class="community-empty">当前没有此状态的额度申请。</p>
      <ul class="community-rows">
        <li v-for="item in page.items" :key="item.id" class="community-row">
          <h3>文章 {{ item.resourceId }} · {{ quotaLabels[item.status] }}</h3>
          <div class="community-meta">
            <span>申请人账号 ID {{ item.requestedBy }}</span>
            <time :datetime="item.createdAt">{{ formatDate(item.createdAt) }}</time>
          </div>
          <p>申请 {{ item.requestedLimit }} 字符<span v-if="item.approvedLimit">，批准 {{ item.approvedLimit }} 字符</span></p>
          <p class="community-literal">{{ item.reason }}</p>
          <p v-if="item.reviewNote" class="community-literal">审批意见 {{ item.reviewNote }}</p>
          <p v-if="item.status === 'PENDING' && item.requestedBy === session.user?.id" class="muted">本人申请由其他额度管理员审批。</p>
          <button v-else-if="item.status === 'PENDING'" type="button" :disabled="busy" @click="begin(item)">处理申请</button>
        </li>
      </ul>
      <CommunityPageBar :result="page" :disabled="loading || busy" label="额度审批分页" @change="load" />
    </template>
    <form v-if="selected" class="community-panel community-policy" @submit.prevent="save">
      <h2>处理文章 {{ selected.resourceId }} 的扩容申请</h2>
      <p v-if="actionError" role="alert" class="error-message">{{ actionError }}</p>
      <fieldset :disabled="busy">
        <legend>审批决定</legend>
        <label class="community-field">结论
          <select v-model="decision">
            <option value="APPROVED">批准扩容</option><option value="REJECTED">拒绝申请</option>
          </select>
        </label>
        <label v-if="decision === 'APPROVED'" class="community-field">批准额度（字符）
          <input v-model.number="approvedLimit" type="number" min="1" :max="selected.requestedLimit" step="1" required />
        </label>
        <label class="community-field">审批意见<textarea v-model="note" rows="4" required></textarea></label>
        <div class="community-actions">
          <button class="community-primary" type="submit">确认审批</button>
          <button type="button" @click="selected = null">取消</button>
        </div>
      </fieldset>
    </form>
  </div>
</template>
