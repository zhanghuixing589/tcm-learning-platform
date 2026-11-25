<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { isAxiosError } from "axios";
import { useSessionStore } from "../../shared/stores/session";
import { latestRequest, publicationLabel } from "./personalCenter";
import { communityApi, flattenSections, sectionTree, type SectionGroup } from "../community/api";
import { bodyCharacterCount, characterCount, communityError, idempotencyKeys, revisionLock } from "../community/editor";
import type { Article, ArticleAction, ArticleInput, Quota, Reference, Section, SourceFormat, TextPolicy } from "../community/types";
import ReferenceEditor from "../community/ReferenceEditor.vue";
import ReferenceOrder from "../community/ReferenceOrder.vue";
import QuotaPanel from "../community/QuotaPanel.vue";
import SectionSelect from "../community/SectionSelect.vue";
import MarkdownEditor from "../community/MarkdownEditor.vue";
import { useUnsavedEditor } from "../community/useUnsavedEditor";
import "./account.css";
import "../community/community.css";
import ComposeNavigation from "../community/ComposeNavigation.vue";
import ComposeGuide from "../community/ComposeGuide.vue";

const route = useRoute();
const router = useRouter();
const session = useSessionStore();
const article = ref<Article | null>(null);
const policy = ref<TextPolicy | null>(null);
const groups = ref<SectionGroup[]>([]);
const form = reactive({ title: "", body: "", sectionId: "", sourceFormat: "MARKDOWN" as SourceFormat, references: [] as Reference[] });
const saved = ref({ ...form });
const loading = ref(false);
const busy = ref(false);
const error = ref("");
const setupError = ref("");
const success = ref("");
const mustRefresh = ref(false);
const writeConflict = ref(false);
const draftSaveState = ref<'idle' | 'saving' | 'saved' | 'failed'>('idle');
const saveBar = ref<HTMLElement>();
watch(saveBar, (node, _old, onCleanup) => {
  if (!node) return;
  const measure = () => node.closest<HTMLElement>('.compose-article')?.style.setProperty('--compose-save-offset', `${node.getBoundingClientRect().height + 8}px`);
  measure();
  if (typeof ResizeObserver !== 'undefined') {
    const observer = new ResizeObserver(measure);
    observer.observe(node);
    onCleanup(() => observer.disconnect());
  }
}, { flush: 'post' });
const keys = idempotencyKeys();
const requests = latestRequest();
let contextVersion = 0;
let internalRoute: string | null = null;

const isNew = computed(() => !article.value && route.params.articleId === "new");
const canWrite = computed(() => !!session.user?.permissions.includes("community:article:submit"));
const editable = computed(() => !mustRefresh.value && canWrite.value &&
  (isNew.value || !!article.value?.capabilities.canEdit));
const referencesChanged = computed(() => JSON.stringify(form.references) !== JSON.stringify(saved.value.references));
const dirty = computed(() => form.title !== saved.value.title || form.body !== saved.value.body
  || form.sectionId !== saved.value.sectionId || form.sourceFormat !== saved.value.sourceFormat || referencesChanged.value);
const draftSaveLabel = computed(() => mustRefresh.value ? '请核对服务器最新稿件'
  : draftSaveState.value === 'saving' ? '正在保存到服务器…'
  : draftSaveState.value === 'failed' ? '保存失败，修改仍保留在编辑器中'
  : dirty.value || !article.value ? '未保存到服务器' : '已保存到服务器');
const canLeave = useUnsavedEditor(dirty, () => internalRoute);
const quota = computed<Quota | null>(() => article.value?.quota ?? (policy.value && {
  bodyCharLimit: policy.value.config.maxArticleChars,
  bodyHardLimit: policy.value.config.maxArticleHardChars,
  maxReferences: policy.value.config.maxReferences,
  maxReferenceChars: policy.value.config.maxReferenceChars,
  latestRequest: null,
}));
const count = computed(() => bodyCharacterCount(form.body));
const overLimit = computed(() => !!quota.value && count.value > quota.value.bodyCharLimit);
const published = computed(() => {
  const value = article.value?.published;
  return value && "section" in value ? value : null;
});
const legacy = computed(() => published.value?.section.code === "legacy-content" && published.value.releaseMethod === "LEGACY");
const canSelectSection = computed(() => editable.value && (!article.value?.publishedRevisionId || legacy.value));
const allSections = computed(() => flattenSections(groups.value));
const section = computed(() => allSections.value.find((item) => item.id === form.sectionId) || null);
const eligible = (item: Section) => item.capabilities.canSubmitArticle && (!legacy.value || item.lifecycle === "FORMAL");

function snapshot(value: Article) {
  const latest = value.latestRevision;
  const sectionId = latest.sectionId ?? value.sectionId;
  return {
    title: latest.title, body: latest.body, sectionId: sectionId && allSections.value.some((item) => item.id === sectionId) ? sectionId : "",
    sourceFormat: latest.sourceFormat, references: latest.references.map((item) => ({ ...item })),
  };
}
function assign(value: Article) {
  article.value = value;
  const next = snapshot(value);
  Object.assign(form, next);
  saved.value = { ...next };
}

async function load() {
  const id = String(route.params.articleId);
  if (internalRoute === route.path && article.value?.id === id) return;
  contextVersion++;
  draftSaveState.value = 'idle';
  keys.clear();
  const ticket = requests.begin();
  error.value = "";
  setupError.value = "";
  success.value = "";
  mustRefresh.value = false;
  writeConflict.value = false;
  busy.value = false;
  article.value = null;
  Object.assign(form, { title: "", body: "", sectionId: "", sourceFormat: "MARKDOWN", references: [] });
  saved.value = { ...form };
  loading.value = true;
  const [policyResult, sectionsResult, value] = await Promise.allSettled([
    communityApi.textPolicy(),
    sectionTree(),
    id === "new" ? Promise.resolve(null) : communityApi.article(id),
  ]);
  if (!requests.isCurrent(ticket)) return;
  policy.value = policyResult.status === "fulfilled" ? policyResult.value : null;
  groups.value = sectionsResult.status === "fulfilled" ? sectionsResult.value : [];
  const failedSetup = [policyResult, sectionsResult].find((result) => result.status === "rejected");
  if (failedSetup?.status === "rejected") setupError.value = communityError(failedSetup.reason);
  if (value.status === "rejected") error.value = communityError(value.reason);
  else if (value.value) assign(value.value);
  else if (typeof route.query.section === "string"
      && allSections.value.some((item) => item.id === route.query.section && eligible(item))) {
    form.sectionId = route.query.section;
    saved.value = { ...form };
  }
  loading.value = false;
}
async function attachIdentity(value: Article, result: "saved" | "identity" | "stale") {
  if (result === "saved") {
    assign(value);
  } else {
    article.value = value;
    if (result === "identity") saved.value = snapshot(value);
  }
  internalRoute = "/account/articles/" + value.id;
  try {
    await router.replace(internalRoute);
    await nextTick();
  } finally {
    internalRoute = null;
  }
}

function validTitle() {
  if (!form.title.trim()) {
    error.value = "请填写文章标题。";
  } else if (policy.value && form.title !== saved.value.title &&
      characterCount(form.title.trim()) > policy.value.config.maxTitleChars) {
    error.value = "标题最多 " + policy.value.config.maxTitleChars + " 字符。";
  } else {
    return true;
  }
  document.getElementById("article-title")?.focus();
  return false;
}

async function runWrite(operation: () => Promise<void>) {
  if (busy.value || mustRefresh.value) return;
  const context = contextVersion;
  busy.value = true;
  error.value = "";
  success.value = "";
  writeConflict.value = false;
  try {
    await operation();
  } catch (cause) {
    if (context === contextVersion) {
      error.value = communityError(cause);
      writeConflict.value = isAxiosError(cause) && cause.response?.status === 409;
    }
  } finally {
    if (context === contextVersion) busy.value = false;
  }
}

async function save() {
  if (!editable.value || busy.value || !validTitle()) return;
  if (!form.body.trim()) {
    error.value = "请填写正文后再保存。";
    document.getElementById("article-body")?.focus();
    return;
  }
  if (overLimit.value) {
    error.value = "正文超出当前额度，请调整正文或申请扩容。";
    document.getElementById("article-body")?.focus();
    return;
  }
  const current = article.value;
  const context = contextVersion;
  const input: ArticleInput = {
    title: form.title, body: form.body, sourceFormat: form.sourceFormat,
    ...(form.sectionId && canSelectSection.value ? { sectionId: form.sectionId } : {}),
    ...(!current || referencesChanged.value ? { references: form.references } : {}),
  };
  draftSaveState.value = 'saving';
  await runWrite(async () => {
    const value = current
      ? await communityApi.saveArticle(current.id, { ...input, ...revisionLock(current) })
      : await communityApi.createArticle(input, keys.for("create-article", input));
    if (context !== contextVersion) return;
    if (value.staleResult || (!current && advancedCreation(value))) return handleStale(value);
    if (current) assign(value);
    else {
      keys.forget("create-article");
      await attachIdentity(value, "saved");
    }
    if (context === contextVersion) success.value = "草稿已保存";
  });
  if (context === contextVersion) draftSaveState.value = error.value || mustRefresh.value ? 'failed' : 'saved';
}

async function createIdentity() {
  if (!isNew.value || !editable.value || !overLimit.value || busy.value || !validTitle()) return;
  const context = contextVersion;
  await runWrite(async () => {
    const input = { title: form.title, sourceFormat: form.sourceFormat };
    const value = await communityApi.draftIdentity(input, keys.for("draft-identity", input));
    if (context !== contextVersion) return;
    if (value.staleResult || advancedCreation(value)) return handleStale(value);
    keys.forget("draft-identity");
    // The response stores only the private identity. The full source remains in the editor.
    await attachIdentity(value, "identity");
    if (context === contextVersion) success.value = "已创建私有草稿身份。正文和文献仍待保存。";
  });
}

function advancedCreation(value: Article) {
  // Creation receipts identify the resource, so replay can return an already-edited draft.
  return value.lockVersion !== 0 || value.latestRevision.versionNo !== 1 || value.latestRevision.state !== "DRAFT";
}
async function handleStale(value: Article) {
  mustRefresh.value = true;
  error.value = "此操作已处理，文章其后已有更新。请重新载入最新版本。";
  if (isNew.value) await attachIdentity(value, "stale");
}
async function action(kind: ArticleAction) {
  const current = article.value;
  if (!current || busy.value || dirty.value) return;
  const allowed = { submit: current.capabilities.canSubmit, withdraw: current.capabilities.canWithdraw, revise: current.capabilities.canRevise };
  if (!allowed[kind] || (kind === "submit" && (!form.body.trim() || overLimit.value))) return;
  const context = contextVersion;
  await runWrite(async () => {
    const input = revisionLock(current);
    const value = await communityApi.articleAction(current.id, kind, input, keys.for(kind, input));
    if (context !== contextVersion) return;
    if (value.staleResult) return handleStale(value);
    keys.forget(kind);
    assign(value);
    success.value = {
      submit: "已提交审核，审核通过后公开展示。",
      withdraw: "已撤回审核，可创建新草稿继续修改。",
      revise: "已创建修订草稿。",
    }[kind];
  });
}
async function reorder(referenceKeys: string[]) {
  const current = article.value;
  if (!current || !editable.value || dirty.value || busy.value) return;
  const context = contextVersion;
  const input = { ...revisionLock(current), referenceKeys };
  await runWrite(async () => {
    const value = await communityApi.reorderReferences(current.id, input, keys.for("reorder", input));
    if (context !== contextVersion) return;
    if (value.staleResult) return handleStale(value);
    keys.forget("reorder");
    assign(value);
    success.value = "文献编号及正文引用已更新。";
  });
}
async function remove() {
  const current = article.value;
  if (!current?.capabilities.canDelete || busy.value || !window.confirm("确定删除这篇草稿吗？删除后无法恢复。")) return;
  const context = contextVersion;
  await runWrite(async () => {
    await communityApi.deleteArticle(current.id, revisionLock(current));
    if (context !== contextVersion) return;
    saved.value = { ...form };
    await router.push("/account?section=publications");
  });
}
async function updateQuota(value: Quota, refreshed: boolean) {
  const current = article.value;
  if (!current) return;
  article.value = { ...current, quota: value };
  if (!refreshed || value.latestRequest?.status === "PENDING" || value.bodyCharLimit >= value.bodyHardLimit
      || !canWrite.value || (current.capabilities.canRequestQuota && current.quota.latestRequest?.status !== "PENDING")) return;
  const updated = article.value;
  const context = contextVersion;
  try {
    const latest = await communityApi.article(current.id);
    if (context !== contextVersion || article.value !== updated) return;
    article.value = { ...updated, capabilities: { ...updated.capabilities, canRequestQuota: latest.capabilities.canRequestQuota } };
  } catch (cause) {
    if (context === contextVersion && article.value === updated) error.value = communityError(cause);
  }
}

watch(() => route.params.articleId, load, { immediate: true });
onBeforeUnmount(() => { contextVersion++; requests.invalidate(); });
</script>

<template>
  <div class="community-page community-editor community-compose compose-article">
    <RouterLink class="community-back" to="/account?section=publications">返回我的发表</RouterLink>
    <header class="community-heading">
      <div>
        <h1>{{ isNew ? "撰写科普文章" : editable ? "编辑科普文章" : "文章稿件" }}</h1>
        <p v-if="article">
          {{ publicationLabel(article.visibilityStatus, article.latestRevision.state) }} · 第 {{ article.latestRevision.versionNo }} 版
        </p>
      </div>
      <RouterLink v-if="article?.published" class="community-button" :to="'/resources/' + article.id">查看公开版本</RouterLink>
    </header>
    <ComposeNavigation kind="ARTICLE" :editing="!isNew" />
    <div class="compose-layout"><div class="compose-main">
    <p v-if="loading" role="status">正在读取稿件…</p>
    <p v-if="error" role="alert" class="error-message">{{ error }}</p>
    <p v-if="setupError" role="alert" class="error-message">{{ setupError }}</p>
    <p v-if="success" role="status" class="success-message">{{ success }}</p>
    <button v-if="mustRefresh || writeConflict || setupError || (!loading && !isNew && !article)"
      type="button" :disabled="busy || loading" @click="canLeave() && load()">重新载入稿件</button>
    <template v-if="!loading && (isNew || article)">
      <div ref="saveBar" class="compose-save-bar"><p class="server-draft-status" role="status">{{ draftSaveLabel }}<small>服务器稿件{{ editable ? ' · 离开前请保存' : ' · 当前只读' }}</small></p><button v-if="editable" type="button" class="community-primary" aria-label="保存草稿（顶部）" :disabled="busy || overLimit" @click="save">{{ draftSaveState === 'saving' ? '保存中…' : '保存草稿' }}</button></div>
      <p v-if="article?.stateReason" class="error-message">{{ article.stateReason }}</p>
      <p v-if="article?.reviewNote" class="success-message">审核意见 {{ article.reviewNote }}</p>
      <p v-if="article?.revisionState === 'IN_REVIEW'" class="muted">审核中的版本已冻结。需要修改时，请先撤回审核。</p>
      <p v-else-if="!editable && !mustRefresh" class="muted">当前稿件只读。</p>
      <p v-if="article?.published && article.publishedRevisionId !== article.latestRevision.revisionId" class="muted">
        审核通过后，修订版会替换当前公开版本。
      </p>
      <form class="community-writing-form" @submit.prevent="save">
        <div class="community-form-grid">
          <label class="community-field compose-title-field">标题（必填）
            <input id="article-title" v-model="form.title" placeholder="写下文章标题" required :readonly="!editable || busy" />
          </label>
          <label v-if="canSelectSection" class="community-field">投稿分区
            <SectionSelect id="article-section" v-model="form.sectionId" :groups="groups" :eligible="eligible"
              :current="section && !eligible(section) ? section : null" :empty-disabled="!!saved.sectionId"
              :disabled="busy || !!setupError" :empty-label="saved.sectionId ? '选择投稿分区' : '暂不分类，提交前选择'" />
          </label>
          <div v-else-if="section || published" class="community-field">投稿分区
            <span>{{ section?.name || published?.section.name }}</span>
          </div>
          <label class="community-field">正文格式
            <select v-model="form.sourceFormat" :disabled="!editable || busy">
              <option value="MARKDOWN">Markdown</option>
              <option value="PLAIN_TEXT">纯文本</option>
            </select>
          </label>
        </div>
        <p v-if="legacy && canSelectSection" class="muted">可为历史文章选择正式分区。候选稿审核通过后，公开文章才会移入该分区。</p>
        <p class="muted">标题 {{ characterCount(form.title) }}<template v-if="policy"> / {{ policy.config.maxTitleChars }}</template> 字符。文章草稿保存到服务器，离开前请点击“保存草稿”。</p>
        <MarkdownEditor id="article-body" v-model="form.body" :source-format="form.sourceFormat" :references="form.references" :policy="policy" :context="String(route.params.articleId)" :readonly="!editable || busy" describedby="article-count" :invalid="overLimit" />
        <p id="article-count" :class="overLimit ? 'community-over-limit' : 'muted'">
          {{ count }}<template v-if="quota"> / {{ quota.bodyCharLimit }}</template> 字符
          <span v-if="overLimit">，已超出当前额度。全文仍保留在编辑器中。</span>
        </p>
        <ReferenceEditor v-if="quota" :key="article?.id || 'new'" v-model="form.references"
          :readonly="!editable || busy" :max-references="quota.maxReferences" :max-reference-chars="quota.maxReferenceChars" />
        <p v-if="article?.danglingReferenceNumbers.length" class="error-message">
          已保存的草稿存在缺失文献的引用 {{ article.danglingReferenceNumbers.join("、") }}，请补齐或移除正文引用。
        </p>
        <div class="community-actions">
          <button v-if="editable" class="community-primary" type="submit" :disabled="busy || overLimit">保存草稿</button>
          <button v-if="editable && isNew && overLimit && quota && quota.bodyCharLimit < quota.bodyHardLimit"
            type="button" data-create-draft-identity :disabled="busy" @click="createIdentity">创建私有草稿以申请扩容</button>
          <button v-if="article?.capabilities.canSubmit" type="button" :disabled="busy || dirty || mustRefresh || overLimit" @click="action('submit')">提交审核</button>
          <button v-if="article?.capabilities.canWithdraw" type="button" :disabled="busy || mustRefresh" @click="action('withdraw')">撤回审核</button>
          <button v-if="article?.capabilities.canRevise" type="button" :disabled="busy || mustRefresh" @click="action('revise')">
            {{ article.revisionState === 'APPROVED' ? '创建修订' : '创建新草稿' }}
          </button>
          <button v-if="article?.capabilities.canDelete" type="button" :disabled="busy || mustRefresh" @click="remove">删除草稿</button>
        </div>
        <p v-if="editable && dirty" class="muted">当前修改尚未保存。</p>
        <p v-if="editable && article && !dirty && !article.capabilities.canSubmit && (!section || !eligible(section)) && !published" class="muted">
          提交审核前，请选择可投稿的分区并保存。
        </p>
      </form>
      <ReferenceOrder v-if="article && editable" :references="form.references"
        :disabled="busy || dirty" @reorder="reorder" />
      <QuotaPanel v-if="article && quota" :key="article.id" :article-id="article.id" :quota="quota"
        :can-request="canWrite && article.capabilities.canRequestQuota" :body-count="count" @quota="updateQuota" />
    </template>
    </div><ComposeGuide kind="ARTICLE" /></div>
  </div>
</template>
