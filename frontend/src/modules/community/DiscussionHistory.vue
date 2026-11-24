<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import type { Page } from "../../shared/api/types";
import { formatDate, latestRequest } from "../account/personalCenter";
import { communityApi } from "./api";
import { communityError } from "./editor";
import type { RevisionDiff, RevisionHeader, SourceChange } from "./types";
import CommunityPageBar from "./CommunityPageBar.vue";

const props = defineProps<{ postId: string; latestRevisionId: string }>();
const open = ref(false);
const history = ref<Page<RevisionHeader> | null>(null);
const loading = ref(false);
const error = ref("");
const before = ref("");
const after = ref("");
const diff = ref<RevisionDiff | null>(null);
const comparing = ref(false);
const diffError = ref("");
const versions = latestRequest();
const diffs = latestRequest();

async function load(page = 1) {
  const ticket = versions.begin();
  loading.value = true;
  error.value = "";
  try {
    const value = await communityApi.revisions(props.postId, page);
    if (!versions.isCurrent(ticket)) return;
    history.value = value;
    if (!after.value) after.value = props.latestRevisionId;
    if (!before.value) before.value = value.items[1]?.revisionId || "";
  } catch (cause) {
    if (versions.isCurrent(ticket)) error.value = communityError(cause);
  } finally {
    if (versions.isCurrent(ticket)) loading.value = false;
  }
}
async function compare() {
  if (!before.value || !after.value || before.value === after.value || comparing.value) return;
  const ticket = diffs.begin();
  comparing.value = true;
  diff.value = null;
  diffError.value = "";
  try {
    const result = await communityApi.diff(before.value, after.value);
    if (diffs.isCurrent(ticket) && result.resourceId === props.postId) diff.value = result;
  } catch (cause) {
    if (diffs.isCurrent(ticket)) diffError.value = communityError(cause);
  } finally {
    if (diffs.isCurrent(ticket)) comparing.value = false;
  }
}
function span(value: string, change: SourceChange, length: number) {
  const chars = Array.from(value);
  const end = change.commonPrefixChars + length;
  return {
    prefix: chars.slice(0, change.commonPrefixChars).join(""),
    changed: chars.slice(change.commonPrefixChars, end).join(""),
    suffix: chars.slice(end).join(""),
  };
}
const comparison = computed(() => {
  const item = diff.value;
  if (!item) return [];
  return [
    {
      label: "原版本", version: item.before.versionNo,
      title: span(item.before.title, item.titleChange, item.titleChange.fromChangedChars),
      body: span(item.before.body, item.bodyChange, item.bodyChange.fromChangedChars),
    },
    {
      label: "对照版本", version: item.after.versionNo,
      title: span(item.after.title, item.titleChange, item.titleChange.toChangedChars),
      body: span(item.after.body, item.bodyChange, item.bodyChange.toChangedChars),
    },
  ];
});
watch(() => [props.postId, props.latestRevisionId], () => {
  versions.invalidate();
  diffs.invalidate();
  history.value = diff.value = null;
  comparing.value = false;
  before.value = after.value = "";
  diffError.value = "";
  if (open.value) void load();
});
watch(open, (value) => { if (value && !history.value) void load(); });
onBeforeUnmount(() => { versions.invalidate(); diffs.invalidate(); });
</script>

<template>
  <section class="community-panel">
    <div class="community-actions">
      <h2>讨论版本历史</h2>
      <button type="button" :aria-expanded="open" @click="open = !open">{{ open ? '收起历史' : '查看历史' }}</button>
    </div>
    <template v-if="open">
      <p v-if="loading" role="status">正在读取版本历史…</p>
      <p v-if="error" role="alert" class="error-message">{{ error }}</p>
      <button v-if="error" type="button" @click="load(history?.page || 1)">重新加载历史</button>
      <template v-if="history">
        <p v-if="!history.items.length" class="community-empty">暂无历史版本。</p>
        <ul class="community-rows">
          <li v-for="item in history.items" :key="item.revisionId" class="community-row">
            <strong>第 {{ item.versionNo }} 版 {{ item.title }}</strong>
            <time class="muted" :datetime="item.createdAt">{{ formatDate(item.createdAt) }}</time>
            <p v-if="item.changeNote" class="community-literal">{{ item.changeNote }}</p>
            <div class="community-actions">
              <button type="button" :aria-pressed="before === item.revisionId" @click="before = item.revisionId">选为原版本</button>
              <button type="button" :aria-pressed="after === item.revisionId" @click="after = item.revisionId">选为对照版本</button>
            </div>
          </li>
        </ul>
        <CommunityPageBar :result="history" :disabled="loading" label="讨论历史分页" @change="load" />
        <button type="button" :disabled="comparing || !before || !after || before === after" @click="compare">比较源文本</button>
      </template>
      <p v-if="comparing" role="status">正在比较源文本…</p>
      <p v-if="diffError" role="alert" class="error-message">{{ diffError }}</p>
      <div v-if="comparison.length" class="community-diff">
        <section v-for="item in comparison" :key="item.label">
          <h3>{{ item.label }} · 第 {{ item.version }} 版</h3>
          <pre>{{ item.title.prefix }}<mark v-if="item.title.changed">{{ item.title.changed }}</mark>{{ item.title.suffix }}</pre>
          <pre>{{ item.body.prefix }}<mark v-if="item.body.changed">{{ item.body.changed }}</mark>{{ item.body.suffix }}</pre>
        </section>
      </div>
    </template>
  </section>
</template>
