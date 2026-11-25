<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { isAxiosError } from "axios";
import { useRoute } from "vue-router";
import { api } from "../../shared/api/client";
import { useSessionStore } from "../../shared/stores/session";
import { stageApi, post, typeLabels, stateLabels, type KnowledgeDetail } from "./api";
import { formatDate } from "../account/personalCenter";
import { useFormulaShelf } from "./formulaShelf";
import FormulaSelectionBar from "./FormulaSelectionBar.vue";
import { useWorkspace } from "./useWorkspace";
import KnowledgeRead from "./KnowledgeRead.vue";
import VersionCompare from "./VersionCompare.vue";
import WorkspaceState from "./WorkspaceState.vue";
import {herbImage} from './herbImages';
import "./stage.css";
import "./library.css";
const route = useRoute(),
  session = useSessionStore(),
  { loading, busy, error, actionError, message, load, act } = useWorkspace();
const item = ref<KnowledgeDetail | null>(null),
  feedback = ref(""),
  bookmarked = ref<boolean | null>(null),
  reason = ref("");
const unavailable = ref(false), shelf = useFormulaShelf();
const image = computed(() => item.value && herbImage(item.value));
const activeSection = ref('body');
let sectionFrame = 0;
function updateSection() {
  sectionFrame = 0;
  const edge = (document.querySelector('.site-header')?.getBoundingClientRect().bottom || 0) + 80;
  let current = sections.value[0]?.id || '';
  for (const section of sections.value) {
    const node = document.getElementById('knowledge-read-' + section.id);
    if (node && node.getBoundingClientRect().top <= edge) current = section.id;
  }
  activeSection.value = current;
}
function scheduleSection() { if (!sectionFrame) sectionFrame = requestAnimationFrame(updateSection); }
onMounted(() => { window.addEventListener('scroll', scheduleSection, {passive:true}); window.addEventListener('resize', scheduleSection); });
onBeforeUnmount(() => { window.removeEventListener('scroll', scheduleSection); window.removeEventListener('resize', scheduleSection); cancelAnimationFrame(sectionFrame); });
let detailAttempt = 0;
const sections = computed(() => {
  const value = item.value;
  if (!value) return [];
  return [{ id: 'body', label: '知识正文' },
    ...(value.herb ? [{ id: 'herb', label: '药材属性' }] : []),
    ...(value.formula ? [{ id: 'formula', label: '方剂信息' }] : []),
    ...(value.ingredients?.length ? [{ id: 'ingredients', label: '方剂组成' }] : []),
    ...(value.evidence?.length ? [{ id: 'evidence', label: '来源依据' }] : []),
    ...(value.relations?.length ? [{ id: 'relations', label: '关联知识' }] : [])];
});
function refresh() {
  const attempt = ++detailAttempt;
  unavailable.value = false;
  bookmarked.value = null;
  item.value = null;
  load(
    async () => {
      try { return await stageApi.detail(String(route.params.id)); }
      catch (cause) {
        if (attempt === detailAttempt) unavailable.value = isAxiosError(cause) && [403,404].includes(cause.response?.status || 0);
        throw cause;
      }
    },
    (result) => {
      item.value = result;
      bookmarked.value =
        typeof result.bookmarked === "boolean" ? result.bookmarked : null;
    },
  );
}
function bookmark() {
  if (!item.value) return;
  const id = item.value.id,
    active = bookmarked.value === true;
  act(
    () =>
      active
        ? api.http.delete(`/resources/${id}/bookmark`)
        : api.http.put(`/resources/${id}/bookmark`),
    () => {
      bookmarked.value = !active;
    },
    active ? "已取消收藏" : "已收藏，可在个人中心查看",
  );
}
function sendFeedback() {
  if (!item.value || !feedback.value.trim()) return;
  act(
    () =>
      post(`/resources/${item.value!.id}/feedback`, {
        content: feedback.value.trim(),
      }),
    () => {
      feedback.value = "";
    },
    "纠错已提交，可在贡献工作台查看处理结果",
  );
}
function suspend() {
  if (!item.value || !reason.value.trim()) return;
  act(
    () =>
      post(`/knowledge/${item.value!.id}/suspend`, {
        reason: reason.value.trim(),
      }),
    () => refresh(),
    "知识已停用",
  );
}
watch(() => route.params.id, refresh, { immediate: true });
// Catalog source links arrive before the async detail has mounted its sections.
watch([() => item.value?.revisionId, () => route.hash], async () => {
  await nextTick();
  const id = route.hash.slice(1);
  if (item.value && sections.value.some(section => id === 'knowledge-read-' + section.id))
    document.getElementById(id)?.scrollIntoView?.();
  updateSection();
}, { flush: 'post' });
</script>
<template>
  <section class="stage-page knowledge-detail">
    <RouterLink class="back-link" to="/knowledge">返回知识中心</RouterLink
    ><div v-if="error && unavailable" class="library-empty" role="alert"><h1>知识暂不可访问</h1><p>该条目可能尚未正式发布、已失效或当前无权查看。</p><RouterLink class="library-secondary" to="/knowledge">返回知识目录</RouterLink></div><WorkspaceState v-else
      :loading="loading"
      :error="error"
      :action-error="actionError"
      :message="message"
      @retry="refresh"
      ><template v-if="item"
        ><header class="stage-heading">
          <div>
            <div class="stage-meta">
              <span class="stage-tag">类型：{{ typeLabels[item.type] }}</span><span
                >第 {{ item.versionNo }} 版 · {{ stateLabels[item.revisionState] || item.revisionState }}</span><span v-if="item.categoryName">{{item.categoryLegacy?'历史目录（待归类）':'目录分类'}}：{{item.categoryName}}</span
              >
            </div>
            <h1>{{ item.canonicalName }}</h1>
            <p v-if="item.title !== item.canonicalName" class="detail-title">题录：{{ item.title }}</p>
            <p v-if="item.aliases?.length" class="detail-aliases">别名：{{ item.aliases.join('、') }}</p>
            <p class="library-hint">{{ stateLabels[item.visibilityStatus] || item.visibilityStatus }}<span v-if="item.updatedAt"> · 更新于 {{formatDate(item.updatedAt)}}</span></p>
          </div>
          <figure v-if="image" class="detail-herb-image"><img :src="image.src" :alt="image.alt" width="640" height="360"/><figcaption>{{image.alt}}</figcaption></figure><div class="detail-actions"><button v-if="item.type === 'FORMULA'" type="button" class="library-secondary" :disabled="shelf.includes(item.id) || shelf.selected.length >= 3" @click="shelf.add(item)">{{shelf.includes(item.id)?'已加入比较':'加入方剂比较'}}</button><el-button
            :loading="busy"
            :type="bookmarked ? 'primary' : 'default'"
            :aria-pressed="bookmarked === true"
            @click="bookmark"
            >{{ bookmarked ? "已收藏" : "收藏知识" }}</el-button
          ></div>
        </header>
        <nav v-if="item.tags?.length" class="knowledge-tag-links" aria-label="通用标签"><template v-for="tag in item.tags" :key="tag.id"><RouterLink v-if="tag.active" :to="{path:'/knowledge',query:{tagId:tag.id}}"># {{tag.name}}</RouterLink><span v-else># {{tag.name}}（已停用）</span></template></nav>
        <FormulaSelectionBar v-if="item.type === 'FORMULA'"/>
        <div class="knowledge-reading-layout" :class="{'without-reading-nav': sections.length < 2}"><KnowledgeRead :item="item" hide-aliases anchor-prefix="knowledge-read" class="knowledge-paper" /><aside v-if="sections.length > 1" class="knowledge-reading-nav" aria-label="阅读导航"><p class="catalog-eyebrow">本页阅读</p><nav><a v-for="section in sections" :key="section.id" :href="'#knowledge-read-' + section.id" :aria-current="activeSection === section.id ? 'location' : undefined">{{section.label}}</a></nav><p class="stage-hint">从正文理解知识，沿依据回到来源。</p><RouterLink to="/knowledge">返回知识目录 →</RouterLink></aside></div><section class="knowledge-maintenance" aria-label="知识版本与质量维护"><h2>知识版本与质量维护</h2><VersionCompare :resource-id="item.id" />
        <details class="stage-details">
          <summary>发现内容问题？提交纠错</summary>
          <form @submit.prevent="sendFeedback">
            <label class="stage-field"
              >纠错内容<textarea
                v-model="feedback"
                required
                maxlength="5000"
                rows="4"
                placeholder="说明具体位置、问题和可核对的来源"
              ></textarea></label
            ><el-button
              type="primary"
              native-type="submit"
              :disabled="!feedback.trim()"
              :loading="busy"
              >提交纠错</el-button
            >
          </form>
        </details>
        <details
          v-if="session.user?.permissions.includes('knowledge:review')"
          class="stage-details"
        >
          <summary>审核操作：停用知识</summary>
          <p>停用后不再进入公开查询；恢复需要新版本审核。</p>
          <form @submit.prevent="suspend">
            <label class="stage-field"
              >停用原因<textarea
                v-model="reason"
                required
                rows="3"
                maxlength="1000"
              ></textarea></label
            ><el-button
              native-type="submit"
              type="danger"
              :loading="busy"
              :disabled="!reason.trim()"
              >停用知识</el-button
            >
          </form>
        </details>
        </section><p class="stage-hint">
          内容用于中医知识学习，不替代专业诊疗。方剂用量为来源记载，不作为个人用药建议。
        </p></template
      ></WorkspaceState
    >
  </section>
</template>
