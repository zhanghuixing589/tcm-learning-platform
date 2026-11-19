<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useSessionStore } from "../../shared/stores/session";
import {
  stageApi,
  sourceTypeLabels,
  stateLabels,
  typeLabels,
  type SourceDocument,
  type KnowledgeDetail,
  type Feedback,
} from "./api";
import { useWorkspace } from "./useWorkspace";
import WorkspaceState from "./WorkspaceState.vue";
import PaginationBar from "./PaginationBar.vue";
import "./stage.css";
const route = useRoute(),
  router = useRouter(),
  session = useSessionStore();
const { loading, error, load } = useWorkspace();
const sources = ref<SourceDocument[]>([]),
  drafts = ref<KnowledgeDetail[]>([]),
  feedback = ref<Feedback[]>([]),
  total = ref(0),
  q = ref(""),
  state = ref("");
const tab = computed(() =>
  ["sources", "knowledge", "feedback"].includes(String(route.query.tab))
    ? String(route.query.tab)
    : "sources",
);
const page = computed(() => Math.max(1, Number(route.query.page) || 1));
const contributor = computed(
  () => !!session.user?.permissions.includes("contribution:submit"),
);
function search(next = 1) {
  router.push({
    path: "/contribute",
    query: {
      tab: tab.value,
      q: q.value || undefined,
      state: state.value || undefined,
      page: next > 1 ? next : undefined,
    },
  });
}
function refresh() {
  q.value = String(route.query.q || "");
  state.value = String(route.query.state || "");
  const params = { q: q.value, state: state.value, page: page.value };
  if (tab.value === "sources")
    load(
      () => stageApi.sources(params),
      (value) => {
        sources.value = value.items;
        total.value = value.total;
      },
    );
  else if (tab.value === "knowledge")
    load(
      () => stageApi.drafts(params),
      (value) => {
        drafts.value = value.items;
        total.value = value.total;
      },
    );
  else
    load(
      () => stageApi.feedback(params),
      (value) => {
        feedback.value = value.items;
        total.value = value.total;
      },
    );
}
watch(() => route.fullPath, refresh, { immediate: true });
</script>
<template>
  <section class="stage-page">
    <header class="stage-heading">
      <div>
        <h1>贡献工作台</h1>
        <p>从来源资料整理知识，追踪稿件审核和纠错处理。</p>
      </div>
      <div v-if="contributor" class="stage-actions">
        <RouterLink class="primary-link" to="/contribute/sources/new"
          >添加来源</RouterLink
        ><RouterLink to="/contribute/knowledge/new">新建知识草稿</RouterLink
        ><RouterLink to="/contribute/import">批量导入</RouterLink>
      </div>
    </header>
    <p v-if="!contributor" class="stage-file-note">
      提交来源和知识需要贡献资格。<RouterLink to="/applications"
        >申请贡献资格</RouterLink
      >。已有纠错可在下方查看。
    </p>
    <nav class="stage-tabs" aria-label="贡献内容">
      <RouterLink
        to="/contribute?tab=sources"
        :aria-current="tab === 'sources' ? 'page' : undefined"
        >资料来源</RouterLink
      ><RouterLink
        to="/contribute?tab=knowledge"
        :aria-current="tab === 'knowledge' ? 'page' : undefined"
        >知识稿件</RouterLink
      ><RouterLink
        to="/contribute?tab=feedback"
        :aria-current="tab === 'feedback' ? 'page' : undefined"
        >我的纠错</RouterLink
      >
    </nav>
    <form
      v-if="tab !== 'feedback'"
      class="stage-toolbar"
      @submit.prevent="search()"
    >
      <label class="stage-field search-field"
        >搜索标题<input v-model="q" maxlength="100" /></label
      ><label class="stage-field"
        >审核状态<select v-model="state">
          <option value="">全部状态</option>
          <option
            v-for="key in [
              'DRAFT',
              'IN_REVIEW',
              'APPROVED',
              'REJECTED',
              'WITHDRAWN',
            ]"
            :key="key"
            :value="key"
          >
            {{ stateLabels[key] }}
          </option>
        </select></label
      ><el-button native-type="submit">筛选</el-button>
    </form>
    <WorkspaceState :loading="loading" :error="error" @retry="refresh"
      ><ul v-if="tab === 'sources' && sources.length" class="stage-list">
        <li v-for="item in sources" :key="item.id">
          <div class="stage-meta">
            <span class="stage-tag">{{
              sourceTypeLabels[item.sourceType]
            }}</span><span>{{ stateLabels[item.reviewState] }}</span
            ><span v-if="item.parseRun"
              >解析：{{
                stateLabels[item.parseRun.status] || item.parseRun.status
              }}</span
            >
          </div>
          <h2>
            <RouterLink :to="`/contribute/sources/${item.id}`">{{
              item.title
            }}</RouterLink>
          </h2>
          <p class="muted">
            {{
              [item.author, item.publisher, item.publicationYear]
                .filter(Boolean)
                .join(" · ") || "尚未填写书目信息"
            }}
          </p>
        </li>
      </ul>
      <ul v-else-if="tab === 'knowledge' && drafts.length" class="stage-list">
        <li v-for="item in drafts" :key="item.revisionId">
          <div class="stage-meta">
            <span class="stage-tag">{{ typeLabels[item.type] }}</span><span>{{
              stateLabels[item.revisionState]
            }}</span
            ><span>第 {{ item.versionNo }} 版</span>
          </div>
          <h2>
            <RouterLink :to="`/contribute/knowledge/${item.id}`">{{
              item.title
            }}</RouterLink>
          </h2>
          <p>{{ item.summary }}</p>
          <p v-if="item.reviewNote" class="stage-hint">
            审核意见：{{ item.reviewNote }}
          </p>
        </li>
      </ul>
      <ul v-else-if="tab === 'feedback' && feedback.length" class="stage-list">
        <li v-for="item in feedback" :key="item.id">
          <div class="stage-meta">
            <span class="stage-tag">{{
              stateLabels[item.status] || item.status
            }}</span><RouterLink :to="`/knowledge/${item.resourceId}`">{{
              item.title || "查看关联知识"
            }}</RouterLink>
          </div>
          <p class="stage-prose">{{ item.content }}</p>
          <p v-if="item.resolution" class="stage-hint">
            处理意见：{{ item.resolution }}
          </p>
        </li>
      </ul>
      <div v-else class="stage-empty">
        <h2>
          {{
            tab === "sources"
              ? "还没有符合条件的来源资料"
              : tab === "knowledge"
                ? "还没有符合条件的知识稿件"
                : "还没有提交过纠错"
          }}
        </h2>
        <p>
          {{
            tab === "feedback"
              ? "在知识详情中说明发现的问题，可以在这里追踪处理结果。"
              : "先添加可靠来源并核对原文，再整理知识草稿。也可以调整搜索条件查看已有内容。"
          }}
        </p>
      </div>
      <PaginationBar :page="page" :total="total" @change="search"
    /></WorkspaceState>
  </section>
</template>
