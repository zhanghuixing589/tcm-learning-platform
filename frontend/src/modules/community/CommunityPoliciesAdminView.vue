<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from "vue";
import type { Page } from "../../shared/api/types";
import { formatDate, latestRequest } from "../account/personalCenter";
import { communityApi } from "./api";
import { characterCount, communityError, idempotencyKeys } from "./editor";
import type { PolicyVersion, TextPolicy, TextPolicyConfig } from "./types";
import CommunityPageBar from "./CommunityPageBar.vue";
import TextPolicyForm from "./TextPolicyForm.vue";
import "./community.css";

const head = ref<TextPolicy | null>(null);
const page = ref<Page<PolicyVersion> | null>(null);
const create = ref(false);
const baseline = ref<TextPolicyConfig | null>(null);
const activation = ref<{ target: PolicyVersion; lockVersion: number } | null>(null);
const reason = ref("");
const loading = ref(false);
const busy = ref(false);
const error = ref("");
const actionError = ref("");
const notice = ref("");
const requests = latestRequest();
const keys = idempotencyKeys();
let mounted = true;
const activeLabel = computed(() => {
  if (!head.value) return "";
  const active = page.value?.items.find((item) => item.policyId === head.value?.policyId);
  return active ? "第 " + active.versionNo + " 版" : "政策 ID " + head.value.policyId;
});
const activationHeadChanged = computed(() =>
  !!activation.value && !!head.value && activation.value.lockVersion !== head.value.lockVersion,
);
async function load(number = 1) {
  const ticket = requests.begin();
  loading.value = true;
  error.value = "";
  try {
    const [current, result] = await Promise.all([communityApi.textPolicy(), communityApi.policies(number)]);
    if (requests.isCurrent(ticket)) {
      head.value = current;
      page.value = result;
    }
  } catch (cause) {
    if (requests.isCurrent(ticket)) error.value = communityError(cause);
  } finally {
    if (requests.isCurrent(ticket)) loading.value = false;
  }
}
function openCreate(config: TextPolicyConfig) {
  baseline.value = config;
  create.value = true;
  activation.value = null;
  actionError.value = notice.value = "";
}
function openActivate(item: PolicyVersion) {
  if (!head.value) return;
  activation.value = { target: item, lockVersion: head.value.lockVersion };
  reason.value = "";
  create.value = false;
  actionError.value = notice.value = "";
}
function recheckActivationHead() {
  const current = head.value;
  const intent = activation.value;
  if (!current || !intent || loading.value || busy.value || !activationHeadChanged.value) return;
  if (!window.confirm(`当前使用${activeLabel.value}。确认以此版本为依据，继续准备启用第 ${intent.target.versionNo} 版吗？`)) return;
  intent.lockVersion = current.lockVersion;
  actionError.value = "";
}
async function save(config: TextPolicyConfig, reason: string) {
  if (busy.value) return;
  busy.value = true;
  actionError.value = notice.value = "";
  try {
    const input = { config, reason };
    const result = await communityApi.createPolicy(input, keys.for("create-policy", input));
    if (!mounted) return;
    keys.forget("create-policy");
    create.value = false;
    notice.value = "第 " + result.versionNo + " 版已保存。";
    await load();
  } catch (cause) {
    if (mounted) actionError.value = communityError(cause);
  } finally {
    if (mounted) busy.value = false;
  }
}
async function activate() {
  const intent = activation.value;
  if (!intent || !head.value || busy.value || intent.target.policyId === head.value.policyId) return;
  actionError.value = notice.value = "";
  if (!reason.value.trim() || characterCount(reason.value) > 1000) {
    actionError.value = "请填写 1 至 1000 字符的启用原因。";
    return;
  }
  const input = { policyId: intent.target.policyId, lockVersion: intent.lockVersion, reason: reason.value };
  busy.value = true;
  try {
    const result = await communityApi.activatePolicy(input, keys.for("activate-policy", input));
    if (!mounted) return;
    keys.forget("activate-policy");
    activation.value = null;
    notice.value = result.policyId === input.policyId ? "政策已启用。" : "已读取当前政策，请核对活动版本。";
    await load(page.value?.page || 1);
  } catch (cause) {
    if (mounted) actionError.value = communityError(cause);
  } finally {
    if (mounted) busy.value = false;
  }
}
void load();
onBeforeUnmount(() => { mounted = false; requests.invalidate(); });
</script>

<template>
  <div class="community-page">
    <header class="community-heading">
      <div><h1>社区文本政策</h1><p v-if="head">当前使用 {{ activeLabel }}</p></div>
      <div class="community-actions">
        <button v-if="head" type="button" class="community-primary" :disabled="busy" @click="openCreate(head.config)">从当前政策新建版本</button>
        <button type="button" :disabled="loading || busy" @click="load(page?.page || 1)">刷新政策</button>
      </div>
    </header>
    <p v-if="loading" role="status">正在读取政策版本…</p>
    <p v-if="error" role="alert" class="error-message">{{ error }}</p>
    <p v-if="actionError" role="alert" class="error-message">{{ actionError }}</p>
    <p v-if="notice" role="status" class="success-message">{{ notice }}</p>
    <TextPolicyForm v-if="create && baseline" :baseline="baseline" :busy="busy" @save="save" />
    <form v-if="activation" class="community-panel" @submit.prevent="activate">
      <h2>启用第 {{ activation.target.versionNo }} 版</h2>
      <label class="community-field">启用原因<textarea v-model="reason" rows="3" required :readonly="busy"></textarea></label>
      <p v-if="activationHeadChanged" class="muted" role="status">当前政策已更新，提交前可重新核对当前政策。</p>
      <div class="community-actions">
        <button class="community-primary" type="submit" :disabled="busy || activation.target.policyId === head?.policyId">确认启用</button>
        <button v-if="activationHeadChanged" type="button" :disabled="loading || busy || activation.target.policyId === head?.policyId" @click="recheckActivationHead">重新核对当前政策</button>
        <button type="button" :disabled="busy" @click="activation = null">取消</button>
      </div>
    </form>
    <template v-if="page">
      <p v-if="!page.items.length && !loading" class="community-empty">暂无政策版本。</p>
      <ul class="community-rows">
        <li v-for="item in page.items" :key="item.policyId" class="community-row">
          <h3>第 {{ item.versionNo }} 版<span v-if="head?.policyId === item.policyId"> · 使用中</span></h3>
          <p class="community-literal">{{ item.reason }}</p>
          <time class="muted" :datetime="item.createdAt">{{ formatDate(item.createdAt) }}</time>
          <details class="community-details community-diff">
            <summary>查看版本配置</summary>
            <pre>{{ JSON.stringify(item.config, null, 2) }}</pre>
          </details>
          <div class="community-actions">
            <button type="button" :disabled="busy" @click="openCreate(item.config)">基于此版新建</button>
            <button v-if="head && head.policyId !== item.policyId" type="button" :disabled="busy" @click="openActivate(item)">启用此版</button>
          </div>
        </li>
      </ul>
      <CommunityPageBar :result="page" :disabled="loading || busy" label="文本政策分页" @change="load" />
    </template>
  </div>
</template>
