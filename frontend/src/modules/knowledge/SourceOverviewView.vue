<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useSessionStore } from "../../shared/stores/session";
import { errorMessage } from "../../shared/api/client";
import { stageApi, post, stateLabels, type SourceDocument } from "./api";
import { readerApi } from "./readerApi";
import SourceProcessingSummary from "./SourceProcessingSummary.vue";
import SourceRead from "./SourceRead.vue";
import SourceView from "./SourceView.vue";
import SourceRightsRestriction from './SourceRightsRestriction.vue';
import "./stage.css";
const route = useRoute(),
  router = useRouter(),
  session = useSessionStore(),
  item = ref<SourceDocument | null>(null),
  error = ref(""),
  busy = ref(false),
  reason = ref(""),
  affected = ref<{ id: string; title: string }[]>([]);
const legacy = computed(() => /^#segment-/.test(route.hash));
const processing = computed(()=>!!item.value&&[item.value.parseRun?.status,item.value.processingJob?.status,item.value.readerBuild?.status].some(status=>['QUEUED','RUNNING','RETRY_WAIT'].includes(status||'')));
let timer: ReturnType<typeof setTimeout> | undefined,
  epoch = 0;
async function refresh() {
  const ticket = ++epoch;
  clearTimeout(timer);
  try {
    const value = await stageApi.source(String(route.params.id));
    if (ticket !== epoch) return;
    item.value = value;
    error.value = "";
    if (
      [value.parseRun?.status,value.processingJob?.status,value.readerBuild?.status].some(status=>["QUEUED", "RUNNING", "RETRY_WAIT"].includes(status||''))
    )
      timer = setTimeout(refresh, 2000);
  } catch (e) {
    if (ticket === epoch) error.value = errorMessage(e);
  }
}
async function action(
  name: "submit" | "withdraw" | "revise" | "retry" | "build",
) {
  if (!item.value) return;
  busy.value = true;
  error.value = "";
  try {
    if (name === "retry") await readerApi.retry(item.value.id);
    else if (name === "build") await readerApi.build(item.value.id);
    else {
      const result = await stageApi.sourceAction(item.value.id, name);
      if (name === "revise") {
        await router.push(`/contribute/sources/${result.id}/edit`);
        return;
      }
    }
    await refresh();
  } catch (e) {
    error.value = errorMessage(e);
  } finally {
    busy.value = false;
  }
}
async function suspend() {
  if (!item.value || !reason.value.trim()) return;
  busy.value = true;
  try {
    const result = await post<{
      affectedResources: { id: string; title: string }[];
    }>(`/source-documents/${item.value.id}/suspend`, {
      reason: reason.value.trim(),
    });
    affected.value = result.affectedResources;
    await refresh();
  } catch (e) {
    error.value = errorMessage(e);
  } finally {
    busy.value = false;
  }
}
watch(() => route.params.id, refresh, { immediate: true });
onBeforeUnmount(() => {
  epoch++;
  clearTimeout(timer);
});
</script>
<template>
  <SourceView v-if="legacy" />
  <section v-else class="stage-page source-workflow">
    <RouterLink to="/contribute?tab=sources">返回来源资料</RouterLink>
    <p v-if="error" role="alert" class="error-message">{{ error }}</p>
    <template v-if="item"
      ><header class="stage-heading">
        <div>
          <h1>{{ item.title }}</h1>
          <p>
            书目概览 · {{ stateLabels[item.reviewState] || item.reviewState }}
          </p>
        </div>
      </header>
      <SourceRead :item="item" />
      <SourceRightsRestriction v-if="item.reviewState==='APPROVED' && item.availability==='ACTIVE' && (item.submittedBy===session.user?.id || session.user?.permissions.includes('knowledge:review'))" :item="item" @changed="refresh" />
      <section class="stage-section">
        <h2>解析与阅读版本</h2>
        <SourceProcessingSummary
          v-if="item.processingSummary"
          :summary="item.processingSummary"
        />
        <p>
          {{ stateLabels[item.parseRun?.status || ""] || "尚未解析" }}
          <span v-if="item.parseRun">· {{ item.parseRun.parseVersion }}</span>
        </p>
        <p v-if="item.processingJob">文字处理任务：{{stateLabels[item.processingJob.status]||item.processingJob.status}}<span v-if="item.processingJob.errorMessage"> · {{item.processingJob.errorMessage}}</span></p>
        <p v-if="item.readerBuild">阅读版构建：{{stateLabels[item.readerBuild.status]||item.readerBuild.status}}<span v-if="item.readerBuild.errorMessage"> · {{item.readerBuild.errorMessage}}</span></p>
        <p v-if="item.parseRun?.errorMessage" role="alert">
          {{ item.parseRun.errorMessage }}
        </p>
        <div class="stage-actions">
          <button @click="refresh">刷新状态</button
          ><template v-if="item.submittedBy === session.user?.id"
            ><RouterLink
              v-if="item.reviewState === 'DRAFT'"
              :to="`/contribute/sources/${item.id}/edit`"
              >编辑书目与授权</RouterLink
            ><button
              v-if="
                item.reviewState === 'DRAFT' &&
                (['RETRY_WAIT', 'DEAD'].includes(item.processingJob?.status || '') || item.parseRun?.status === 'FAILED' && !processing)
              "
              :disabled="busy"
              @click="action('retry')"
            >
              重试解析</button
            ><button
              v-if="item.reviewState === 'DRAFT' && (!!item.readingEditionId || !item.fileId && !!item.sourceUrl)"
              :disabled="busy"
              @click="action('submit')"
            >
              提交来源审核</button
            ><button
              v-if="item.reviewState === 'IN_REVIEW'"
              :disabled="busy"
              @click="action('withdraw')"
            >
              撤回来源审核</button
            ><button
              v-if="['REJECTED', 'WITHDRAWN'].includes(item.reviewState)"
              :disabled="busy"
              @click="action('revise')"
            >
              创建来源新稿</button
            ><button
              v-if="
                !item.readingEditionId && item.parseRun?.status === 'SUCCEEDED'
              "
              :disabled="busy"
              @click="action('build')"
            >
              为历史资料补建阅读版
            </button></template
          >
        </div>
      </section>
      <details
        v-if="
          session.user?.permissions.includes('knowledge:review') &&
          item.reviewState === 'APPROVED' &&
          item.availability === 'ACTIVE'
        "
        class="stage-details"
      >
        <summary>审核操作：停用来源</summary>
        <form @submit.prevent="suspend">
          <label class="stage-field"
            >停用原因<textarea
              v-model="reason"
              rows="3"
              required
              maxlength="1000"
            /></label
          ><button :disabled="busy || !reason.trim()">
            停用来源并查看影响
          </button>
        </form>
      </details>
      <section v-if="affected.length">
        <h2>受影响的知识</h2>
        <ul>
          <li v-for="resource in affected" :key="resource.id">
            {{ resource.title }}
          </li>
        </ul>
        <p>恢复需新来源与知识版本审核，不能直接恢复下游。</p>
      </section></template
    >
    <p v-else-if="!error" role="status">正在加载书目…</p>
  </section>
</template>
