<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { latestRequest } from "../account/personalCenter";
import { communityApi } from "./api";
import { communityError } from "./editor";
import type { Section } from "./types";

const props = defineProps<{ sectionId: string; initial?: Section }>();
const emit = defineEmits<{ updated: [value: Section] }>();
const section = ref<Section | null>(null);
const loading = ref(false);
const busy = ref(false);
const error = ref("");
const status = ref("");
const editLock = ref<number | null>(null);
const editing = computed(() => editLock.value !== null);
const description = ref("");
const rules = ref("");
const requests = latestRequest();
let epoch = 0;

async function load() {
  if (busy.value) return;
  const ticket = requests.begin();
  loading.value = true;
  error.value = "";
  try {
    const value = await communityApi.section(props.sectionId);
    if (requests.isCurrent(ticket)) {
      section.value = value;
      emit("updated", value);
    }
  } catch (cause) {
    if (requests.isCurrent(ticket)) error.value = communityError(cause);
  } finally {
    if (requests.isCurrent(ticket)) loading.value = false;
  }
}
function beginEdit() {
  if (!section.value?.capabilities.canEditProfile) return;
  description.value = section.value.description || "";
  rules.value = section.value.rulesText || "";
  editLock.value = section.value.lockVersion;
  error.value = "";
}
function reloadForm() {
  if (!window.confirm("载入最新配置会放弃当前未保存的分区修改，确定继续吗？")) return;
  beginEdit();
}
async function mutate(update: (item: Section) => Promise<Section>, confirmation: string) {
  if (!section.value || busy.value) return;
  const current = epoch;
  requests.invalidate();
  loading.value = false;
  busy.value = true;
  error.value = "";
  status.value = "";
  try {
    const value = await update(section.value);
    if (current !== epoch) return;
    section.value = value;
    emit("updated", value);
    status.value = confirmation;
    editLock.value = null;
  } catch (cause) {
    if (current === epoch) error.value = communityError(cause);
  } finally {
    if (current === epoch) busy.value = false;
  }
}
function membership(join: boolean) {
  const item = section.value;
  if (editing.value || !item || (join ? !item.capabilities.canJoin : !item.capabilities.canLeave)) return;
  void mutate(async (value) => {
    await communityApi.membership(value.id, join);
    return communityApi.section(value.id);
  }, join ? "已加入分区。" : "已退出分区。");
}
function save() {
  const version = editLock.value;
  if (!section.value?.capabilities.canEditProfile || version === null) return;
  void mutate((item) => communityApi.sectionProfile(item.id, {
    lockVersion: version, description: description.value, rulesText: rules.value,
  }), "分区介绍与规则已更新。");
}
watch(() => props.sectionId, () => {
  epoch++;
  requests.invalidate();
  busy.value = loading.value = false;
  editLock.value = null;
  error.value = status.value = "";
  section.value = props.initial ?? null;
  if (!section.value) void load();
}, { immediate: true });
onBeforeUnmount(() => { epoch++; requests.invalidate(); });
</script>

<template>
  <aside class="community-panel">
    <h2>分区规则</h2>
    <p v-if="loading" role="status">正在读取分区规则…</p>
    <p v-if="error" role="alert" class="error-message">{{ error }}</p>
    <button v-if="error" type="button" :disabled="busy || loading" @click="load">刷新分区状态</button>
    <p v-if="status" role="status" class="success-message">{{ status }}</p>
    <template v-if="section">
      <p v-if="section.description" class="community-literal">{{ section.description }}</p>
      <p class="community-literal">{{ section.rulesText || '本分区暂无补充规则。' }}</p>
      <p class="muted">{{ section.memberCount }} 位成员<span v-if="section.joined">，已加入</span></p>
      <p v-if="!section.writable" class="muted">本分区目前只读。</p>
      <p v-if="section.ownerId && section.joined && !section.capabilities.canLeave && section.capabilities.canEditProfile" class="muted">
        分区负责人需要先联系管理员交接。
      </p>
      <div class="community-actions">
        <button v-if="section.capabilities.canJoin" type="button" :disabled="busy || editing" @click="membership(true)">加入分区</button>
        <button v-if="section.capabilities.canLeave" type="button" :disabled="busy || editing" @click="membership(false)">退出分区</button>
        <button v-if="section.capabilities.canEditProfile && !editing" type="button" :disabled="busy" @click="beginEdit">编辑介绍与规则</button>
      </div>
      <p v-if="editing && (section.capabilities.canJoin || section.capabilities.canLeave)" class="muted">
        保存或取消当前修改后，可以加入或退出分区。
      </p>
      <form v-if="editing" class="community-quota-form" @submit.prevent="save">
        <p v-if="editLock !== section.lockVersion" class="muted" role="status">分区已有更新。请核对最新配置后重新编辑。</p>
        <label class="community-field">分区介绍<textarea v-model="description" :readonly="busy" rows="3"></textarea></label>
        <label class="community-field">分区规则<textarea v-model="rules" :readonly="busy" rows="5"></textarea></label>
        <div class="community-actions">
          <button class="community-primary" type="submit" :disabled="busy || !section.capabilities.canEditProfile">保存分区介绍与规则</button>
          <button v-if="editLock !== section.lockVersion && section.capabilities.canEditProfile" type="button"
            :disabled="busy" @click="reloadForm">载入最新配置</button>
          <button type="button" :disabled="busy" @click="editLock = null">取消</button>
        </div>
      </form>
    </template>
  </aside>
</template>
