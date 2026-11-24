<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { useSessionStore } from "../../shared/stores/session";
import { stageApi, stateLabels, type ReviewDetail, type Segment } from "./api";
import { useWorkspace } from "./useWorkspace";
import WorkspaceState from "./WorkspaceState.vue";
import KnowledgeRead from "./KnowledgeRead.vue";
import SourceRead from "./SourceRead.vue";
import VersionCompare from "./VersionCompare.vue";
import CommunityMarkdown from "../community/CommunityMarkdown.vue";
import CommunityReferences from "../community/CommunityReferences.vue";
import { communityApi } from "../community/api";
import type { TextPolicy } from "../community/types";
import "./stage.css";
const route = useRoute(),
  session = useSessionStore(),
  { loading, busy, error, actionError, message, load, act } = useWorkspace();
const item = ref<ReviewDetail | null>(null),
  articlePolicy = ref<TextPolicy | null>(null),
  segments = ref<Segment[]>([]),
  note = ref(""),
  decision = ref<"APPROVED" | "REJECTED">("APPROVED");
const self = computed(
  () => !!item.value && item.value.submittedBy === session.user?.id,
);
const pending = computed(
  () =>
    !!item.value &&
    ["PENDING", "CLAIMED", "IN_PROGRESS"].includes(item.value.status),
);
const mine = computed(() => item.value?.reviewerId === session.user?.id);
function refresh() {
  item.value = null;
  note.value = "";
  segments.value = [];
  articlePolicy.value = null;
  load(
    async () => {
      const detail = await stageApi.review(String(route.params.id));
      const parts =
        detail.document?.parseRun?.status === "SUCCEEDED"
          ? (await stageApi.segments(detail.document.id, { pageSize: 100 }))
              .items
          : [];
      const policy = detail.article
        ? await communityApi.textPolicy().catch(() => null)
        : null;
      return { detail, parts, policy };
    },
    (value) => {
      item.value = value.detail;
      segments.value = value.parts;
      articlePolicy.value = value.policy;
    },
  );
}
function claim() {
  if (!item.value || self.value) return;
  const id = item.value.id,
    version = item.value.lockVersion;
  act(
    async () => {
      await stageApi.claim(id, version);
      return stageApi.review(id);
    },
    (value) => (item.value = value),
    "已领取审核任务",
  );
}
function submit() {
  if (!item.value || self.value || !pending.value || !mine.value) return;
  if (!note.value.trim()) {
    actionError.value = "请填写审核意见，说明核对依据与结论";
    document.getElementById("review-note")?.focus();
    return;
  }
  const id = item.value.id,
    version = item.value.lockVersion;
  act(
    async () => {
      const result = await stageApi.decide(
        id,
        version,
        decision.value,
        note.value.trim(),
      );
      return result;
    },
    (value) => {
      item.value = value;
    },
    decision.value === "APPROVED"
      ? "审核已通过"
      : "审核已退回，作者可复制新稿后修订",
  );
}
watch(() => route.params.id, refresh, { immediate: true });
</script>
<template>
  <section class="stage-page">
    <RouterLink class="back-link" to="/reviews">返回审核队列</RouterLink
    ><WorkspaceState
      :loading="loading"
      :error="error"
      :action-error="actionError"
      :message="message"
      @retry="refresh"
      ><template v-if="item"
        ><header class="stage-heading">
          <div>
            <span class="stage-tag">{{
              item.kind === "SOURCE"
                ? "来源审核"
                : item.kind === "ARTICLE"
                  ? "文章审核"
                  : "知识审核"
            }}</span>
            <h1>{{ item.title }}</h1>
            <p>
              提交人：{{ item.submitterName }} ·
              {{ stateLabels[item.status] || item.status }}
            </p>
          </div>
          <el-button @click="refresh">刷新任务状态</el-button>
        </header>
        <p v-if="self" class="error-message" role="alert">
          不能审核本人提交的内容，请由其他专业审核者处理。
        </p>
        <section v-if="item.document" class="stage-section">
          <h2>待审核来源</h2>
          <SourceRead :item="item.document" />
          <p v-if="item.document.parseRun" class="stage-hint">
            解析：{{
              stateLabels[item.document.parseRun.status] ||
              item.document.parseRun.status
            }}
            · {{ item.document.parseRun.parseVersion }}
          </p>
          <ul class="stage-list">
            <li v-for="segment in segments" :key="segment.id">
              <h3>
                片段 {{ segment.segmentNo }}
                <span class="muted">{{ segment.pageLabel }}</span>
              </h3>
              <p class="stage-prose">{{ segment.body }}</p>
            </li>
          </ul>
          <RouterLink :to="`/sources/${item.document.id}`"
            >查看完整来源与全部片段</RouterLink
          >
        </section>
        <section v-if="item.knowledge" class="stage-section">
          <h2>待审核知识</h2>
          <KnowledgeRead :item="item.knowledge" /><VersionCompare
            :resource-id="item.knowledge.id"
          />
        </section>
        <section v-if="item.article" class="stage-section">
          <h2>待审核文章</h2>
          <h3>{{ item.article.title }}</h3>
          <p v-if="item.article.section" class="muted">投稿分区 {{ item.article.section.name }}</p>
          <CommunityMarkdown :body="item.article.body" :source-format="item.article.sourceFormat"
            :references="item.article.references" :policy="articlePolicy" />
          <CommunityReferences :references="item.article.references || []" :policy="articlePolicy" />
        </section>
        <section class="stage-section">
          <h2>审核处理</h2>
          <p v-if="!pending">
            此任务已完成：{{ stateLabels[item.status] || item.status }}。<span
              v-if="item.reviewNote"
              >审核意见：{{ item.reviewNote }}</span
            >
          </p>
          <template v-else-if="!self"
            ><p v-if="item.reviewerId && !mine" class="stage-file-note">
              该任务已被其他审核者领取。刷新后可查看最新处理结果。
            </p>
            <div v-else-if="!mine">
              <p class="stage-hint">
                请先领取任务，再核对来源、正文和结构化事实。
              </p>
              <el-button type="primary" :loading="busy" @click="claim"
                >领取审核任务</el-button
              >
            </div>
            <form v-else @submit.prevent="submit">
              <p class="stage-hint">
                审核意见必须说明核对依据。任务状态变化或锁版本冲突时，请刷新后重新核对。
              </p>
              <label class="stage-field"
                >审核结论<select v-model="decision">
                  <option value="APPROVED">通过审核</option>
                  <option value="REJECTED">退回修订</option>
                </select></label
              ><label class="stage-field" for="review-note"
                >审核意见（必填）<textarea
                  id="review-note"
                  v-model="note"
                  required
                  maxlength="5000"
                  rows="5"
                  placeholder="说明来源可靠性、事实核对与需要修订的具体问题"
                ></textarea></label
              ><el-button type="primary" native-type="submit" :loading="busy"
                >确认审核结论</el-button
              >
            </form></template
          >
        </section>
        <section v-if="item.events?.length" class="stage-section">
          <h2>处理记录</h2>
          <ul class="stage-list">
            <li v-for="(event, index) in item.events" :key="event.id || index">
              <p>
                {{
                  stateLabels[
                    event.decision || event.eventType || event.action || ""
                  ] ||
                  event.eventType ||
                  event.decision ||
                  event.action
                }}
                <span class="muted">{{ event.actorName }}</span>
              </p>
              <p v-if="event.note">{{ event.note }}</p>
              <time class="muted">{{ event.createdAt }}</time>
            </li>
          </ul>
        </section></template
      ></WorkspaceState
    >
  </section>
</template>
