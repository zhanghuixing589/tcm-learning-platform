<script setup lang="ts">
import { isAxiosError } from "axios";
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from "vue";
import type { Page } from "../../shared/api/types";
import { communityApi } from "./api";
import { bodyCharacterCount, communityError, idempotencyKeys } from "./editor";
import type { Comment, CommentCreate, CommentEdit, TextPolicy } from "./types";
import CommentItem from "./CommentItem.vue";
import CommunityPageBar from "./CommunityPageBar.vue";

const props = defineProps<{ resourceId: string; canComment: boolean; policy: TextPolicy | null; commentsEnabled?: boolean }>();
type CommentPage = { data: Page<Comment> | null; loading: boolean; error: string; requestedPage: number; ticket: number; open: boolean };
type Conflict = { state: "refresh" | "unavailable" } | { state: "review"; latest: Comment };
const empty = (): CommentPage => ({ data: null, loading: false, error: "", requestedPage: 1, ticket: 0, open: false });
const roots = reactive(empty());
const replies = reactive<Record<string, CommentPage>>({});
const body = ref("");
const target = ref<Comment | null>(null);
const mode = ref<"reply" | "edit" | "root">("root");
const conflict = ref<Conflict | null>(null);
const busy = ref(false);
const error = ref("");
const notice = ref("");
const composer = ref<HTMLTextAreaElement>();
const keys = idempotencyKeys();
let epoch = 0;
let targetPage = 1;
let originalEditSource: string | null = null;
const limit = computed(() => props.policy?.config.maxCommentChars);
const size = computed(() => bodyCharacterCount(body.value));
const overLimit = computed(() => !!limit.value && size.value > limit.value
  && (mode.value !== "edit" || body.value !== target.value?.body));

async function load(state: CommentPage, page: number, rootId?: string) {
  const current = epoch;
  const ticket = ++state.ticket;
  state.requestedPage = page;
  state.loading = true;
  state.error = "";
  try {
    const result = await communityApi.comments(props.resourceId, page, rootId);
    if (current === epoch && state.ticket === ticket) {
      state.data = result;
      const selected = target.value;
      if (selected && result.items.some((item) => item.id === selected.id && item.displayState !== "VISIBLE")) {
        markTargetUnavailable(selected);
      }
      return { result };
    }
  } catch (cause) {
    if (current === epoch && state.ticket === ticket) {
      state.error = communityError(cause);
      return { cause };
    }
  } finally {
    if (current === epoch && state.ticket === ticket) state.loading = false;
  }
}
function loadReplies(id: string, page: number) {
  const state = replies[id];
  if (state) return load(state, page, id);
}
function toggleReplies(id: string) {
  if (!replies[id]) replies[id] = empty();
  const state = replies[id];
  state.open = !state.open;
  if (state.open && !state.data && !state.loading) void loadReplies(id, 1);
}
function pageFor(item: Comment) {
  return item.rootCommentId ? replies[item.rootCommentId] : roots;
}
function select(item: Comment, action: "reply" | "edit") {
  if (!props.canComment || busy.value || !(action === "reply" ? item.capabilities.canReply : item.capabilities.canEdit)) return;
  if (mode.value === action && target.value?.id === item.id) {
    composer.value?.focus();
    return;
  }
  if (body.value && !window.confirm("当前评论尚未保存，确定切换编辑对象吗？")) return;
  target.value = item;
  targetPage = pageFor(item)?.data?.page ?? 1;
  mode.value = action;
  conflict.value = null;
  body.value = action === "edit" ? item.body ?? "" : "";
  originalEditSource = action === "edit" ? body.value : null;
  error.value = "";
  void nextTick(() => composer.value?.focus());
}
function resetComposer() {
  target.value = null;
  conflict.value = null;
  originalEditSource = null;
  mode.value = "root";
  body.value = "";
}
function cancel() {
  if (body.value && !window.confirm("确定放弃当前评论修改吗？")) return;
  resetComposer();
  error.value = "";
}
function replace(item: Comment) {
  const state = pageFor(item);
  if (state?.data) state.data.items = state.data.items.map((old) => old.id === item.id ? item : old);
  return state;
}

function markTargetUnavailable(selected: Comment) {
  if (body.value === originalEditSource) body.value = "";
  originalEditSource = null;
  keys.forget((mode.value === "edit" ? "edit:" : "create:") + selected.id);
  target.value = null;
  conflict.value = { state: "unavailable" };
  error.value = "";
}

async function refreshTarget() {
  const selected = target.value;
  if (!selected || busy.value || conflict.value?.state !== "refresh") return;
  const state = pageFor(selected);
  if (!state) return;
  const current = epoch;
  busy.value = true;
  try {
    const read = await load(state, targetPage, selected.rootCommentId || undefined);
    if (!read || current !== epoch || selected !== target.value) return;
    if ("cause" in read) {
      const status = isAxiosError(read.cause) ? read.cause.response?.status : undefined;
      error.value = state.error;
      state.error = "";
      if (status !== 403 && status !== 404) return;
      if (state.data) state.data.items = state.data.items.filter((item) => item.id !== selected.id);
    }
    const latest = read.result?.items.find((item) => item.id === selected.id);
    const canContinue = latest?.displayState === "VISIBLE" && (mode.value === "edit"
      ? latest.capabilities.canEdit && !!latest.revisionId : latest.capabilities.canReply);
    if (canContinue) {
      conflict.value = { state: "review", latest };
      error.value = "";
    } else {
      markTargetUnavailable(selected);
    }
  } finally {
    if (current === epoch) busy.value = false;
  }
}
function adoptTarget() {
  if (busy.value || conflict.value?.state !== "review") return;
  if (!window.confirm("确认采用当前评论版本并保留你填写的内容吗？")) return;
  target.value = conflict.value.latest;
  conflict.value = null;
  error.value = "";
}

async function save() {
  if (busy.value || !props.canComment || conflict.value) return;
  if (mode.value !== "root" && (!target.value || (mode.value === "edit" && !target.value.revisionId))) return;
  if (!body.value.trim() || overLimit.value) {
    error.value = "请输入符合当前字符额度的评论。";
    composer.value?.focus();
    return;
  }
  const current = epoch;
  const selected = target.value;
  const editing = mode.value === "edit";
  const rootId = mode.value === "reply" && selected ? selected.rootCommentId || selected.id : undefined;
  const operation = editing ? "edit:" + selected?.id : "create:" + (selected?.id ?? "root");
  busy.value = true;
  error.value = "";
  notice.value = "";
  try {
    if (editing && selected?.revisionId) {
      const input: CommentEdit = { body: body.value, baseRevisionId: selected.revisionId, lockVersion: selected.lockVersion };
      const value = await communityApi.editComment(selected.id, input, keys.for(operation, input));
      if (current !== epoch || selected !== target.value) return;
      if (value.lockVersion >= selected.lockVersion) replace(value);
      if (selected.rootCommentId) void loadReplies(selected.rootCommentId, replies[selected.rootCommentId]?.data?.page || 1);
      else void load(roots, roots.data?.page || 1);
    } else {
      const input: CommentCreate = rootId && selected
        ? { body: body.value, rootCommentId: rootId, replyToCommentId: selected.id }
        : { body: body.value };
      await communityApi.createComment(props.resourceId, input, keys.for(operation, input));
      if (current !== epoch || selected !== target.value) return;
      if (rootId) {
        if (!replies[rootId]) replies[rootId] = empty();
        const state = replies[rootId];
        state.open = true;
        const total = state.data?.total ?? (selected?.rootCommentId ? 0 : selected?.replyCount) ?? 0;
        const page = Math.ceil((total + 1) / (state.data?.pageSize || 20));
        void loadReplies(rootId, page);
      }
      const rootPage = Math.ceil(((roots.data?.total || 0) + 1) / (roots.data?.pageSize || 20));
      void load(roots, rootId ? roots.data?.page || 1 : rootPage);
    }
    keys.forget(operation);
    resetComposer();
    notice.value = editing ? "评论已更新。" : "评论已发布。";
  } catch (cause) {
    if (current === epoch && selected === target.value) {
      error.value = communityError(cause);
      if (selected && isAxiosError(cause) && cause.response?.status === 409) conflict.value = { state: "refresh" };
    }
  } finally {
    if (current === epoch) busy.value = false;
  }
}
async function remove(item: Comment) {
  if (!item.capabilities.canDelete || busy.value) return;
  const editingThis = target.value?.id === item.id;
  const question = editingThis && body.value ? "确定删除评论并放弃当前编辑吗？" : "确定删除评论吗？";
  if (!window.confirm(question + "已有回复会保留。")) return;
  const current = epoch;
  busy.value = true;
  error.value = "";
  notice.value = "";
  try {
    const value = await communityApi.deleteComment(item.id, item.lockVersion);
    if (current !== epoch) return;
    const page = replace(value);
    // An in-flight read may have started before the delete committed.
    if (page?.loading) void load(page, page.requestedPage, value.rootCommentId || undefined);
    if (editingThis) resetComposer();
    notice.value = "评论已删除，已有回复仍可查看。";
  } catch (cause) {
    if (current === epoch) error.value = communityError(cause);
  } finally {
    if (current === epoch) busy.value = false;
  }
}
watch(() => props.resourceId, () => {
  epoch++;
  keys.clear();
  resetComposer();
  Object.assign(roots, empty());
  for (const id of Object.keys(replies)) delete replies[id];
  busy.value = false;
  error.value = notice.value = "";
  void load(roots, 1);
}, { immediate: true });
onBeforeUnmount(() => { epoch++; });
</script>

<template>
  <section aria-label="评论" class="community-comments">
    <h2>评论</h2>
    <p v-if="roots.loading" role="status">正在读取评论…</p>
    <div v-if="roots.error">
      <p role="alert" class="error-message">{{ roots.error }}</p>
      <button type="button" @click="load(roots, roots.requestedPage)">重新加载评论</button>
    </div>
    <template v-if="roots.data">
      <p v-if="!roots.data.items.length" class="community-empty">{{ roots.data.total ? "本页没有评论。" : "还没有评论。" }}</p>
      <ul class="community-rows">
        <li v-for="item in roots.data.items" :key="item.id" class="community-row">
          <CommentItem :item="item" :busy="busy" :writable="canComment" @reply="select($event, 'reply')" @edit="select($event, 'edit')" @remove="remove" />
          <button v-if="item.replyCount || replies[item.id]?.open" type="button" :data-replies="item.id"
            :aria-expanded="!!replies[item.id]?.open" @click="toggleReplies(item.id)">
            {{ replies[item.id]?.open ? '收起回复' : '查看回复' }}（{{ item.replyCount }}）
          </button>
          <section v-if="replies[item.id]?.open" class="community-replies" aria-label="评论回复">
            <p v-if="replies[item.id].loading" role="status">正在读取回复…</p>
            <div v-if="replies[item.id].error">
              <p role="alert" class="error-message">{{ replies[item.id].error }}</p>
              <button type="button" @click="loadReplies(item.id, replies[item.id].requestedPage)">重新加载回复</button>
            </div>
            <template v-if="replies[item.id].data">
              <p v-if="!replies[item.id].data!.items.length" class="community-empty">本页没有回复。</p>
              <ul class="community-rows">
                <li v-for="reply in replies[item.id].data!.items" :key="reply.id">
                  <CommentItem :item="reply" :busy="busy" :writable="canComment"
                    @reply="select($event, 'reply')" @edit="select($event, 'edit')" @remove="remove" />
                </li>
              </ul>
              <CommunityPageBar :result="replies[item.id].data!" :disabled="replies[item.id].loading" label="回复分页"
                @change="loadReplies(item.id, $event)" />
            </template>
          </section>
        </li>
      </ul>
      <CommunityPageBar :result="roots.data" :disabled="roots.loading" label="根评论分页" @change="load(roots, $event)" />
    </template>
    <p v-if="error" role="alert" class="error-message">{{ error }}</p>
    <p v-if="notice" role="status" class="success-message">{{ notice }}</p>
    <form v-if="canComment || body" @submit.prevent="save">
      <p v-if="conflict?.state === 'unavailable'" role="status" class="muted">
        目标评论已不可用。<template v-if="body">以下草稿仅自己可见。</template>
      </p>
      <div v-else-if="conflict?.state === 'review'" class="community-panel">
        <h3>当前评论内容</h3>
        <p class="community-literal">{{ conflict.latest.body }}</p>
      </div>
      <label class="community-field">
        {{ conflict?.state === 'unavailable' ? '未发送草稿' : mode === 'edit' ? '编辑评论' : mode === 'reply' ? '回复 ' + (target?.author?.nickname || '评论') : '写评论' }}
        <textarea ref="composer" v-model="body" rows="3" required :readonly="busy || !canComment || conflict?.state === 'unavailable'"
          aria-describedby="comment-count" :aria-invalid="overLimit"></textarea>
      </label>
      <p id="comment-count" :class="overLimit ? 'community-over-limit' : 'muted'">
        纯文本 · {{ size }}<template v-if="limit"> / {{ limit }}</template> 字符
      </p>
      <div class="community-actions">
        <button v-if="conflict?.state === 'refresh'" type="button" :disabled="busy" @click="refreshTarget">刷新目标评论</button>
        <button v-else-if="conflict?.state === 'review'" type="button" :disabled="busy || !canComment" @click="adoptTarget">采用当前版本</button>
        <button v-if="conflict?.state !== 'unavailable'" class="community-primary" type="submit"
          :disabled="busy || overLimit || !canComment || !!conflict">
          {{ mode === 'edit' ? '保存评论' : mode === 'reply' ? '发布回复' : '发布评论' }}
        </button>
        <button v-if="mode !== 'root'" type="button" :disabled="busy" @click="cancel">取消</button>
      </div>
    </form>
    <p v-if="!canComment" class="muted">{{ commentsEnabled === false ? "此帖已关闭评论。" : "当前仅可阅读评论。" }}</p>
  </section>
</template>
