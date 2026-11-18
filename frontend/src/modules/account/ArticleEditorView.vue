<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from "vue";
import {
  onBeforeRouteLeave,
  onBeforeRouteUpdate,
  useRoute,
  useRouter,
} from "vue-router";
import { api, errorMessage } from "../../shared/api/client";
import type { Envelope } from "../../shared/api/types";
import { useSessionStore } from "../../shared/stores/session";
import { latestRequest, publicationLabel } from "./personalCenter";
import AccountIcon from "./AccountIcon.vue";
import "./account.css";
interface Article {
  id: string;
  title: string;
  body: string;
  revisionId: string;
  revisionState: string;
  visibilityStatus: string;
  reviewNote: string | null;
  stateReason?: string | null;
}
const route = useRoute(),
  router = useRouter();
const session = useSessionStore();
const article = ref<Article | null>(null),
  form = reactive({ title: "", body: "" }),
  saved = ref(JSON.stringify(form)),
  loading = ref(false),
  busy = ref(false),
  error = ref(""),
  success = ref("");
let contextVersion = 0;
const requests = latestRequest(),
  isNew = computed(() => route.params.articleId === "new"),
  isPost = computed(() => route.name === "own-post"),
  canWrite = computed(
    () =>
      !!session.user?.permissions.includes("community:article:submit") &&
      !isPost.value,
  ),
  editable = computed(
    () =>
      canWrite.value &&
      (isNew.value || article.value?.revisionState === "DRAFT"),
  ),
  dirty = computed(() => saved.value !== JSON.stringify(form));
function assign(value: Article) {
  article.value = value;
  form.title = value.title;
  form.body = value.body;
  saved.value = JSON.stringify(form);
}
async function load() {
  contextVersion++;
  busy.value = false;
  const ticket = requests.begin();
  error.value = "";
  success.value = "";
  article.value = null;
  if (isNew.value) {
    form.title = "";
    form.body = "";
    saved.value = JSON.stringify(form);
    loading.value = false;
    return;
  }
  loading.value = true;
  try {
    const response = await api.http.get<Envelope<Article>>(
      `/me/${isPost.value ? "publications" : "articles"}/${route.params.articleId}`,
    );
    if (requests.isCurrent(ticket)) assign(response.data.data);
  } catch (e) {
    if (requests.isCurrent(ticket)) error.value = errorMessage(e);
  } finally {
    if (requests.isCurrent(ticket)) loading.value = false;
  }
}
async function save() {
  if (busy.value || !editable.value) return;
  const context = contextVersion,
    creating = isNew.value;
  if (!form.title.trim() || !form.body.trim()) {
    error.value = "请填写标题和正文后再保存";
    return;
  }
  busy.value = true;
  error.value = "";
  success.value = "";
  try {
    const input = { title: form.title.trim(), body: form.body.trim() };
    const response = creating
      ? await api.http.post<Envelope<Article>>("/me/articles", input)
      : await api.http.patch<Envelope<Article>>(
          `/me/articles/${article.value!.id}`,
          input,
        );
    const value = response.data.data;
    if (context !== contextVersion) return;
    assign(value);
    if (creating) await router.replace(`/account/articles/${value.id}`);
    if (String(route.params.articleId) === value.id)
      success.value = "草稿已保存";
  } catch (e) {
    if (context === contextVersion) error.value = errorMessage(e);
  } finally {
    if (context === contextVersion) busy.value = false;
  }
}
async function action(kind: "submit" | "withdraw" | "revise") {
  if (!article.value || busy.value) return;
  const context = contextVersion;
  busy.value = true;
  error.value = "";
  success.value = "";
  try {
    const value = (
      await api.http.post<Envelope<Article>>(
        `/me/articles/${article.value.id}/${kind}`,
      )
    ).data.data;
    if (context !== contextVersion) return;
    assign(value);
    success.value =
      kind === "submit"
        ? "已提交审核，审核通过后公开展示"
        : kind === "withdraw"
          ? "已撤回，可创建新草稿继续修改"
          : "已创建修订草稿";
  } catch (e) {
    if (context === contextVersion) error.value = errorMessage(e);
  } finally {
    if (context === contextVersion) busy.value = false;
  }
}
async function remove() {
  if (
    !article.value ||
    busy.value ||
    !window.confirm("确定删除这篇草稿吗？删除后无法恢复。")
  )
    return;
  busy.value = true;
  const context = contextVersion;
  try {
    await api.http.delete(`/me/articles/${article.value.id}`);
    if (context !== contextVersion) return;
    saved.value = JSON.stringify(form);
    await router.push("/account?section=publications");
  } catch (e) {
    if (context === contextVersion) error.value = errorMessage(e);
  } finally {
    if (context === contextVersion) busy.value = false;
  }
}
onBeforeRouteLeave(
  () => !dirty.value || window.confirm("草稿还没有保存，确定离开吗？"),
);
onBeforeRouteUpdate(
  () => !dirty.value || window.confirm("草稿还没有保存，确定离开吗？"),
);
function beforeUnload(event: BeforeUnloadEvent) {
  if (dirty.value) {
    event.preventDefault();
    event.returnValue = "";
  }
}
window.addEventListener("beforeunload", beforeUnload);
onBeforeUnmount(() => {
  contextVersion++;
  requests.invalidate();
  window.removeEventListener("beforeunload", beforeUnload);
});
watch(() => [route.params.articleId, route.name], load, { immediate: true });
</script>
<template>
  <div class="account-subpage">
    <RouterLink class="account-back" to="/account?section=publications"
      ><AccountIcon name="back" :size="18" />返回我的发表</RouterLink
    >
    <div class="settings-heading">
      <h1>{{ isNew ? "写一篇分享" : editable ? "编辑内容" : "发表详情" }}</h1>
      <p>分享有来源的知识，正文中请注明参考资料与出处。</p>
    </div>
    <p v-if="error" role="alert" class="error-message">{{ error }}</p>
    <p v-if="success" role="status" class="success-message">{{ success }}</p>
    <el-skeleton v-if="loading" :rows="8" animated /><template
      v-else-if="isNew || article"
      ><div v-if="article" class="article-state">
        <span>{{
          publicationLabel(article.visibilityStatus, article.revisionState)
        }}</span><RouterLink
          v-if="article.visibilityStatus === 'PUBLISHED'"
          :to="`/resources/${article.id}`"
          >查看公开版本</RouterLink
        >
      </div>
      <p v-if="article?.stateReason" class="error-message">
        状态说明：{{ article.stateReason }}
      </p>
      <p v-if="article?.reviewNote" class="success-message">
        审核意见：{{ article.reviewNote }}
      </p>
      <p v-if="!canWrite && !isPost" class="muted">
        当前账号没有文章投稿权限，仍可查看已提交内容。
      </p>
      <el-form label-position="top" :disabled="busy" @submit.prevent="save"
        ><el-form-item label="标题" for="article-title"
          ><el-input
            id="article-title"
            v-model="form.title"
            maxlength="255"
            required
            :readonly="!editable" /></el-form-item
        ><el-form-item label="正文" for="article-body"
          ><el-input
            id="article-body"
            v-model="form.body"
            type="textarea"
            :rows="16"
            maxlength="100000"
            required
            :readonly="!editable"
            placeholder="写下你的学习发现，并在文末注明资料出处。支持保存 Markdown 文本。"
        /></el-form-item>
        <div class="article-actions">
          <el-button
            v-if="editable"
            type="primary"
            native-type="submit"
            :loading="busy"
            >保存草稿</el-button
          ><el-button
            v-if="canWrite && article?.revisionState === 'DRAFT'"
            :disabled="busy || dirty"
            @click="action('submit')"
            >提交审核</el-button
          ><el-button
            v-if="!isPost && article?.revisionState === 'IN_REVIEW'"
            :disabled="busy"
            @click="action('withdraw')"
            >撤回审核</el-button
          ><el-button
            v-if="
              canWrite &&
              article &&
              ['APPROVED', 'REJECTED', 'WITHDRAWN'].includes(
                article.revisionState,
              )
            "
            type="primary"
            :disabled="busy"
            @click="action('revise')"
            >{{
              article.revisionState === "APPROVED" ? "创建修订" : "创建新草稿"
            }}</el-button
          ><el-button
            v-if="!isPost && article?.revisionState === 'DRAFT'"
            :disabled="busy"
            @click="remove"
            >删除草稿</el-button
          >
        </div>
        <p
          v-if="article?.revisionState === 'DRAFT' && dirty"
          class="muted"
          style="margin-top: 12px; font-size: 13px"
        >
          保存修改后即可提交审核。
        </p></el-form
      ></template
    >
  </div>
</template>
