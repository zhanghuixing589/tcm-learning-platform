<script setup lang="ts">
import { onBeforeUnmount, reactive, ref } from "vue";
import { latestRequest } from "../account/personalCenter";
import { communityApi, sectionTree, type SectionGroup } from "./api";
import { communityError, idempotencyKeys } from "./editor";
import type { Section, SectionCreate, SectionUpdate } from "./types";
import "./community.css";

const groups = ref<SectionGroup[]>([]);
const loading = ref(false);
const busy = ref(false);
const error = ref("");
const loadError = ref("");
const message = ref("");
const selected = ref<Section | null>(null);
const creating = ref(false);
const form = reactive({ parentId: "", name: "", code: "", description: "", rulesText: "", ownerId: "", sortOrder: 0, active: true });
const requests = latestRequest();
const keys = idempotencyKeys();
let mounted = true;

async function load() {
  const ticket = requests.begin();
  loading.value = true;
  loadError.value = "";
  try {
    const result = await sectionTree();
    if (requests.isCurrent(ticket)) groups.value = result;
  } catch (cause) {
    if (requests.isCurrent(ticket)) loadError.value = communityError(cause);
  } finally {
    if (requests.isCurrent(ticket)) loading.value = false;
  }
}
function begin(item?: Section) {
  selected.value = item ?? null;
  creating.value = !item;
  error.value = message.value = "";
  Object.assign(form, {
    parentId: item?.parentId ?? "", name: item?.name ?? "", code: item?.code ?? "",
    description: item?.description ?? "", rulesText: item?.rulesText ?? "",
    ownerId: item?.ownerId ?? "", sortOrder: item?.sortOrder ?? 0, active: item?.active ?? true,
  });
}
async function save() {
  if (busy.value || (!creating.value && !selected.value)) return;
  const edit = selected.value;
  const common = { description: form.description, rulesText: form.rulesText, sortOrder: Number(form.sortOrder) };
  error.value = message.value = "";
  if (!Number.isInteger(common.sortOrder) || common.sortOrder < -2147483648 || common.sortOrder > 2147483647) {
    error.value = "排序值必须为有效整数。";
    return;
  }
  busy.value = true;
  try {
    let value: Section;
    if (edit) {
      const input: SectionUpdate = { ...common, lockVersion: edit.lockVersion, active: form.active };
      value = await communityApi.updateSection(edit.id, input);
    } else {
      const input: SectionCreate = { ...common, parentId: form.parentId || null, name: form.name,
        code: form.code, ownerId: form.ownerId || null };
      value = await communityApi.createSection(input, keys.for("create", input));
    }
    if (!mounted) return;
    if (!edit) keys.forget("create");
    begin(value);
    message.value = edit ? "分区配置已更新。" : "分区已创建。";
    await load();
  } catch (cause) {
    if (mounted) error.value = communityError(cause);
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
      <div><h1>分区管理</h1><p>配置一级和二级社区分区。</p></div>
      <div class="community-actions">
        <button class="community-primary" type="button" :disabled="busy" @click="begin()">新建分区</button>
        <button type="button" :disabled="busy || loading" @click="load">刷新分区</button>
      </div>
    </header>
    <p v-if="loadError" role="alert" class="error-message">{{ loadError }}</p>
    <p v-if="loading" role="status">正在读取分区…</p>
    <p v-if="message" role="status" class="success-message">{{ message }}</p>
    <div class="community-layout">
      <section aria-label="已有分区">
        <p v-if="!loading && !groups.length" class="community-empty">暂无分区。</p>
        <ul class="community-rows">
          <li v-for="{ section, children } in groups" :key="section.id" class="community-row">
            <button type="button" :disabled="busy" :aria-pressed="selected?.id === section.id" @click="begin(section)">
              {{ section.name }}<span v-if="!section.writable">（只读）</span>
            </button>
            <ul v-if="children.length" class="community-admin-children">
              <li v-for="child in children" :key="child.id">
                <button type="button" :disabled="busy" :aria-pressed="selected?.id === child.id" @click="begin(child)">
                  {{ child.name }}<span v-if="!child.writable">（只读）</span>
                </button>
              </li>
            </ul>
          </li>
        </ul>
      </section>
      <form v-if="creating || selected" class="community-panel community-editor" @submit.prevent="save">
        <h2>{{ creating ? '新建分区' : '配置 ' + selected?.name }}</h2>
        <p v-if="error" role="alert" class="error-message">{{ error }}</p>
        <fieldset :disabled="busy">
          <legend>分区设置</legend>
          <template v-if="creating">
            <div class="community-form-grid">
              <label class="community-field">上级分区
                <select v-model="form.parentId">
                  <option value="">建立一级分区</option>
                  <option v-for="{ section } in groups.filter((group) => group.section.writable)" :key="section.id" :value="section.id">{{ section.name }}</option>
                </select>
              </label>
              <label class="community-field">名称<input v-model="form.name" required /></label>
              <label class="community-field">编码<input v-model="form.code" required pattern="[a-z0-9][a-z0-9_-]{0,63}" /></label>
              <label class="community-field">初始负责人账号 ID（选填）
                <input v-model="form.ownerId" inputmode="numeric" pattern="[1-9][0-9]*" />
              </label>
            </div>
          </template>
          <p v-else class="muted">编码 {{ selected?.code }} · 负责人账号 ID {{ selected?.ownerId || '未指定' }}</p>
          <label class="community-field">排序值<input v-model.number="form.sortOrder" type="number" step="1" required /></label>
          <label v-if="selected" class="community-check"><input v-model="form.active" type="checkbox" /> 启用分区</label>
          <label class="community-field">分区介绍<textarea v-model="form.description" rows="3"></textarea></label>
          <label class="community-field">分区规则<textarea v-model="form.rulesText" rows="5"></textarea></label>
          <button class="community-primary" type="submit">{{ creating ? '创建分区' : '保存配置' }}</button>
        </fieldset>
      </form>
    </div>
  </div>
</template>
