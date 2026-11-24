<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useSessionStore } from "../../shared/stores/session";
import { latestRequest } from "../account/personalCenter";
import { communityApi, flattenSections, sectionTree, type SectionGroup } from "./api";
import { bodyCharacterCount, characterCount, communityError, idempotencyKeys, revisionLock } from "./editor";
import type { OwnPost, Section, TextPolicy } from "./types";
import DiscussionHistory from "./DiscussionHistory.vue";
import SectionSelect from "./SectionSelect.vue";
import SourcePreview from "./SourcePreview.vue";
import { useUnsavedEditor } from "./useUnsavedEditor";
import "./community.css";

const route = useRoute();
const router = useRouter();
const session = useSessionStore();
const item = ref<OwnPost | null>(null);
const policy = ref<TextPolicy | null>(null);
const groups = ref<SectionGroup[]>([]);
const form = reactive({ title: "", body: "", sectionId: "", changeNote: "" });
const saved = ref({ ...form });
const loading = ref(false);
const busy = ref(false);
const error = ref("");
const setupError = ref("");
const message = ref("");
const stale = ref(false);
const requests = latestRequest();
const keys = idempotencyKeys();
let context = 0;
const id = computed(() => typeof route.params.articleId === "string" ? route.params.articleId : "");
const canPost = computed(() => !!session.user?.permissions.includes("community:post"));
const canEdit = computed(() => canPost.value && !stale.value && (!id.value || !!item.value?.capabilities.canEdit));
const dirty = computed(() => form.title !== saved.value.title || form.body !== saved.value.body
  || form.sectionId !== saved.value.sectionId || form.changeNote !== saved.value.changeNote);
const canLeave = useUnsavedEditor(dirty);
const eligible = (section: Section) => section.capabilities.canPost;
const sectionName = computed(() => item.value?.published?.section.name
  || flattenSections(groups.value).find((entry) => entry.id === form.sectionId)?.name);
const count = computed(() => bodyCharacterCount(form.body));
const overLimit = computed(() => !!policy.value && form.body !== saved.value.body && count.value > policy.value.config.maxPostChars);

function assign(value: OwnPost) {
  item.value = value;
  Object.assign(form, { title: value.latestRevision.title, body: value.latestRevision.body,
    sectionId: value.latestRevision.sectionId || "", changeNote: "" });
  saved.value = { ...form };
}
async function load() {
  context++;
  const ticket = requests.begin();
  const postId = id.value;
  keys.clear();
  item.value = null;
  loading.value = true;
  busy.value = stale.value = false;
  error.value = setupError.value = message.value = "";
  Object.assign(form, { title: "", body: "", sectionId: "", changeNote: "" });
  saved.value = { ...form };
  const [text, sections, own] = await Promise.allSettled([
    communityApi.textPolicy(),
    sectionTree(),
    postId ? communityApi.ownPost(postId) : Promise.resolve(null),
  ]);
  if (!requests.isCurrent(ticket)) return;
  policy.value = text.status === "fulfilled" ? text.value : null;
  groups.value = sections.status === "fulfilled" ? sections.value : [];
  const failed = [text, sections].find((value) => value.status === "rejected");
  if (failed?.status === "rejected") setupError.value = communityError(failed.reason);
  if (own.status === "rejected") error.value = communityError(own.reason);
  else if (own.value) assign(own.value);
  else if (typeof route.query.section === "string"
      && flattenSections(groups.value).some((section) => section.id === route.query.section && eligible(section))) {
    form.sectionId = route.query.section;
    saved.value = { ...form };
  }
  loading.value = false;
}
function validationError() {
  if (!form.title.trim() || !form.body.trim()) return "请填写讨论标题和正文。";
  if (policy.value && form.title !== saved.value.title &&
      characterCount(form.title.trim()) > policy.value.config.maxTitleChars) {
    return "标题最多 " + policy.value.config.maxTitleChars + " 字符。";
  }
  if (overLimit.value) return "正文超出当前额度。";
  if (!id.value && !form.sectionId) return "请选择可发布讨论的分区。";
  if (characterCount(form.changeNote) > 1000) return "修改说明最多 1000 字符。";
  return "";
}
async function save() {
  if (!canEdit.value || busy.value || !dirty.value) return;
  error.value = validationError();
  if (error.value) return;
  const current = context;
  const post = item.value;
  busy.value = true;
  message.value = "";
  try {
    if (!post) {
      const input = { title: form.title, body: form.body, sectionId: form.sectionId, sourceFormat: "MARKDOWN" as const };
      const created = await communityApi.createPost(input, keys.for("create", input));
      if (current !== context) return;
      keys.forget("create");
      saved.value = { ...form };
      await router.push("/community/posts/" + created.id);
    } else {
      const input = { ...revisionLock(post), title: form.title, body: form.body, changeNote: form.changeNote || null };
      const result = await communityApi.editPost(post.id, input, keys.for("edit", input));
      if (current !== context) return;
      const latest = await communityApi.ownPost(post.id);
      if (current !== context) return;
      keys.forget("edit");
      if (latest.latestRevision.revisionId !== result.revisionId) {
        stale.value = true;
        error.value = "此修订已发布，讨论随后有更新。请重新载入最新版本。";
        return;
      }
      assign(latest);
      message.value = "讨论修订已发布。";
    }
  } catch (cause) {
    if (current === context) error.value = communityError(cause);
  } finally {
    if (current === context) busy.value = false;
  }
}
watch(id, load, { immediate: true });
onBeforeUnmount(() => { context++; requests.invalidate(); });
</script>

<template>
  <div class="community-page community-editor">
    <RouterLink class="community-back" :to="id ? '/account?section=publications' : '/community'">
      {{ id ? '返回我的发表' : '返回科普社区' }}
    </RouterLink>
    <header class="community-heading">
      <div>
        <h1>{{ id ? '我的讨论' : '发布讨论' }}</h1>
        <p v-if="item">第 {{ item.latestRevision.versionNo }} 版<span v-if="sectionName"> · {{ sectionName }}</span></p>
        <p v-else-if="!id">讨论发布后可在分区中阅读和回复。</p>
      </div>
      <RouterLink v-if="item?.published" :to="'/community/posts/' + item.id" class="community-button">查看公开讨论</RouterLink>
    </header>
    <p v-if="loading" role="status">正在读取讨论…</p>
    <p v-if="error" role="alert" class="error-message">{{ error }}</p>
    <p v-if="setupError" role="alert" class="error-message">{{ setupError }}</p>
    <p v-if="message" role="status" class="success-message">{{ message }}</p>
    <button v-if="error || setupError" type="button" :disabled="busy || loading" @click="canLeave() && load()">重新载入</button>
    <template v-if="!loading && (!id || item)">
      <p v-if="!canEdit" class="muted">当前讨论只读。</p>
      <form @submit.prevent="save">
        <label v-if="!id" class="community-field">讨论分区
          <SectionSelect v-model="form.sectionId" :groups="groups" :eligible="eligible" :disabled="!canEdit || busy"
            empty-label="选择分区" required />
        </label>
        <label class="community-field">标题
          <input v-model="form.title" id="discussion-title" required :readonly="!canEdit || busy" />
        </label>
        <label class="community-field">正文
          <textarea id="discussion-body" v-model="form.body" rows="14" required
            :readonly="!canEdit || busy" aria-describedby="discussion-count" :aria-invalid="overLimit"></textarea>
        </label>
        <p id="discussion-count" :class="overLimit ? 'community-over-limit' : 'muted'">
          {{ (item?.latestRevision.sourceFormat || 'MARKDOWN') === 'MARKDOWN' ? 'Markdown' : '纯文本' }}
          · {{ count }}<template v-if="policy"> / {{ policy.config.maxPostChars }}</template> 字符
        </p>
        <label v-if="id && canEdit" class="community-field">修改说明（选填）
          <textarea v-model="form.changeNote" rows="2" :readonly="busy"></textarea>
        </label>
        <button v-if="canEdit" class="community-primary" type="submit" :disabled="busy || !dirty || overLimit">
          {{ id ? '发布修订' : '发布讨论' }}
        </button>
      </form>
      <SourcePreview :body="form.body" :source-format="item?.latestRevision.sourceFormat || 'MARKDOWN'" :policy="policy" :context="id" />
      <DiscussionHistory v-if="item" :post-id="item.id" :latest-revision-id="item.latestRevision.revisionId" />
    </template>
  </div>
</template>
