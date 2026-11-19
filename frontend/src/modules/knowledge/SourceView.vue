<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useSessionStore } from "../../shared/stores/session";
import { errorMessage } from "../../shared/api/client";
import {
  stageApi,
  post,
  sourceTypeLabels,
  stateLabels,
  type KnowledgeDetail,
  type KnowledgeType,
  type Segment,
  type SourceDocument,
  type SourceInput,
  typeLabels,
} from "./api";
import { useWorkspace } from "./useWorkspace";
import WorkspaceState from "./WorkspaceState.vue";
import SourceRead from "./SourceRead.vue";
import PaginationBar from "./PaginationBar.vue";
import "./stage.css";
const route = useRoute(),
  router = useRouter(),
  session = useSessionStore();
const { loading, busy, error, actionError, message, load, act, reset } =
  useWorkspace();
const item = ref<SourceDocument | null>(null),
  form = ref<SourceInput>(blank()),
  savedForm = ref(""),
  file = ref<File | null>(null),
  fileName = ref(""),
  segments = ref<Segment[]>([]),
  segmentTotal = ref(0),
  segmentPage = ref(1),
  segmentError = ref(""),
  selected = ref<string[]>([]),
  extractType = ref<KnowledgeType>("CONCEPT"),
  reason = ref(""),
  affected = ref<{ id: string; title: string }[]>([]);
let timer: ReturnType<typeof setTimeout> | undefined,
  epoch = 0;
const isNew = computed(() => route.params.id === "new");
const own = computed(() => item.value?.submittedBy === session.user?.id);
const contributor = computed(
  () => !!session.user?.permissions.includes("contribution:submit"),
);
const reviewer = computed(
  () => !!session.user?.permissions.includes("knowledge:review"),
);
const editable = computed(
  () =>
    contributor.value &&
    (isNew.value || (own.value && item.value?.reviewState === "DRAFT")),
);
const dirty = computed(
  () =>
    editable.value &&
    (!!file.value ||
      (savedForm.value !== "" &&
        JSON.stringify(form.value) !== savedForm.value)),
);
const parsing = computed(() =>
  ["QUEUED", "RUNNING", "RETRY_WAIT"].includes(
    item.value?.parseRun?.status || "",
  ),
);
function blank(): SourceInput {
  return {
    title: "",
    sourceType: "BOOK",
    author: "",
    publisher: "",
    sourceUrl: "",
    bibliographicNote: "",
  };
}
function apply(value: SourceDocument) {
  item.value = value;
  form.value = {
    fileId: value.fileId || undefined,
    title: value.title,
    sourceType: value.sourceType,
    author: value.author || "",
    publisher: value.publisher || "",
    publicationYear: value.publicationYear || undefined,
    sourceUrl: value.sourceUrl || "",
    bibliographicNote: value.bibliographicNote || "",
  };
  fileName.value = value.fileName || "";
  savedForm.value = JSON.stringify(form.value);
}
async function loadSegments(page = 1) {
  if (!item.value || item.value.parseRun?.status !== "SUCCEEDED") {
    segments.value = [];
    segmentTotal.value = 0;
    return;
  }
  const ticket = epoch;
  segmentError.value = "";
  try {
    const result = await stageApi.segments(item.value.id, {
      page,
      pageSize: 20,
    });
    if (ticket !== epoch) return;
    segments.value = result.items;
    segmentTotal.value = result.total;
    segmentPage.value = page;
  } catch (e) {
    if (ticket === epoch) segmentError.value = errorMessage(e);
  }
}
function poll() {
  clearTimeout(timer);
  if (!parsing.value || !item.value) return;
  const id = item.value.id,
    ticket = epoch;
  timer = setTimeout(async () => {
    try {
      const value = await stageApi.source(id);
      if (ticket !== epoch || item.value?.id !== id) return;
      item.value = value;
      if (value.parseRun?.status === "SUCCEEDED") await loadSegments();
      else poll();
    } catch (e) {
      if (ticket === epoch)
        segmentError.value = `${errorMessage(e)}。请刷新解析状态。`;
    }
  }, 2000);
}
function refresh() {
  epoch++;
  reset();
  clearTimeout(timer);
  item.value = null;
  file.value = null;
  selected.value = [];
  segments.value = [];
  segmentError.value = "";
  affected.value = [];
  fileName.value = "";
  savedForm.value = "";
  if (isNew.value) {
    form.value = blank();
    loading.value = false;
    return;
  }
  load(
    () => stageApi.source(String(route.params.id)),
    (value) => {
      apply(value);
      loadSegments();
      poll();
    },
  );
}
function chooseFile(event: Event) {
  file.value = (event.target as HTMLInputElement).files?.[0] || null;
  actionError.value = "";
  if (
    file.value &&
    (file.value.size > 10 * 1024 * 1024 ||
      !/\.(txt|docx|pdf)$/i.test(file.value.name))
  ) {
    actionError.value = "请选择不超过 10MB 的 UTF-8 TXT、DOCX 或带文本层 PDF。";
    file.value = null;
    (event.target as HTMLInputElement).value = "";
  }
}
async function save() {
  if (!editable.value || !form.value.title.trim()) return;
  const id = isNew.value ? null : String(route.params.id);
  const input = { ...form.value, title: form.value.title.trim() };
  const uploadedFile = file.value;
  const ticket = epoch;
  act(
    async () => {
      let fileId = input.fileId;
      if (uploadedFile) {
        const result = await stageApi.upload(uploadedFile);
        if (ticket !== epoch) throw new Error("资料页面已变化，旧上传不再保存");
        fileId = result.id;
      }
      return stageApi.saveSource(id, {
        ...input,
        fileId,
      });
    },
    (value) => {
      apply(value);
      file.value = null;
      if (isNew.value) router.replace(`/contribute/sources/${value.id}`);
    },
    "来源资料已保存",
  );
}
function parse() {
  if (!item.value) return;
  act(
    () => stageApi.parse(item.value!.id),
    (value) => {
      item.value!.parseRun = value;
      poll();
    },
    "已创建解析任务，请等待处理",
  );
}
function sourceAction(action: "submit" | "withdraw" | "revise") {
  if (!item.value) return;
  act(
    () => stageApi.sourceAction(item.value!.id, action),
    (value) => {
      apply(value);
      if (action === "revise") router.push(`/contribute/sources/${value.id}`);
    },
    {
      submit: "来源已提交审核",
      withdraw: "审核已撤回，原资料保持冻结，可创建新稿",
      revise: "已复制为新资料草稿",
    }[action],
  );
}
function extract() {
  if (!item.value || !selected.value.length) return;
  act(
    () =>
      post<{ items: KnowledgeDetail[] }>("/knowledge/extractions", {
        documentId: item.value!.id,
        segmentIds: selected.value,
        type: extractType.value,
      }),
    (value) => {
      if (value.items[0])
        router.push(`/contribute/knowledge/${value.items[0].id}`);
    },
    "已生成待人工校正的知识草稿",
  );
}
function suspend() {
  if (!item.value || !reason.value.trim()) return;
  act(
    () =>
      post<{ affectedResources: { id: string; title: string }[] }>(
        `/source-documents/${item.value!.id}/suspend`,
        { reason: reason.value.trim() },
      ),
    (value) => {
      affected.value = value.affectedResources;
      item.value!.availability = "SUSPENDED";
    },
    "来源已停用，受影响知识不再进入合格检索",
  );
}
watch(() => route.fullPath, refresh, { immediate: true });
onBeforeUnmount(() => {
  epoch++;
  clearTimeout(timer);
});
</script>
<template>
  <section class="stage-page">
    <RouterLink
      class="back-link"
      :to="
        route.path.startsWith('/contribute')
          ? '/contribute?tab=sources'
          : '/knowledge'
      "
      >{{
        route.path.startsWith("/contribute") ? "返回贡献工作台" : "返回知识中心"
      }}</RouterLink><WorkspaceState
      :loading="loading"
      :error="error"
      :action-error="actionError"
      :message="message"
      @retry="refresh"
      ><header class="stage-heading">
        <div>
          <h1>{{ isNew ? "添加来源资料" : item?.title }}</h1>
          <p v-if="isNew">先保存书目信息与原始资料，再解析、核对并提交审核。</p>
          <p v-else-if="item">
            {{ stateLabels[item.reviewState] || item.reviewState }} ·
            {{ stateLabels[item.availability] || item.availability }}
          </p>
        </div>
      </header>
      <form v-if="editable" @submit.prevent="save">
        <div class="stage-grid">
          <label class="stage-field"
            >资料标题<input
              v-model="form.title"
              required
              maxlength="255" /></label
          ><label class="stage-field"
            >来源类型<select v-model="form.sourceType">
              <option
                v-for="(label, type) in sourceTypeLabels"
                :key="type"
                :value="type"
              >
                {{ label }}
              </option>
            </select></label
          ><label class="stage-field"
            >作者<input v-model="form.author" maxlength="255" /></label
          ><label class="stage-field"
            >出版单位<input v-model="form.publisher" maxlength="255" /></label
          ><label class="stage-field"
            >出版年份<input
              v-model.number="form.publicationYear"
              type="number"
              min="1"
              max="9999" /></label
          ><label class="stage-field"
            >来源网址<input
              v-model="form.sourceUrl"
              aria-label="来源网址"
              type="url"
              placeholder="https://"
              maxlength="1500"
            /><small>网址仅保存为书目信息，平台不会自动抓取网页。</small></label
          >
        </div>
        <label class="stage-field"
          >书目说明<textarea
            v-model="form.bibliographicNote"
            rows="3"
            maxlength="5000"
          ></textarea></label
        ><label class="stage-field"
          >原始资料<input
            aria-label="原始资料"
            type="file"
            accept=".txt,.docx,.pdf"
            :disabled="!!item?.parseRun"
            @change="chooseFile"
          /><small>{{
            fileName
              ? `已关联：${fileName}。${item?.parseRun ? "已有解析记录，更换文件请新建资料。" : "解析前可替换本草稿文件。"}`
              : "支持 UTF-8 TXT、DOCX 和有文本层的 PDF，单个文件不超过 10MB。不支持扫描件 OCR。"
          }}</small></label
        ><el-button type="primary" native-type="submit" :loading="busy"
          >保存来源资料</el-button
        >
      </form>
      <SourceRead v-else-if="item" :item="item" /><template v-if="item"
        ><section class="stage-section">
          <h2>资料解析</h2>
          <p v-if="!item.fileId">
            本资料只包含书目信息，未关联文件，无法解析。可在知识草稿中手工录入引文与位置。
          </p>
          <template v-else
            ><p v-if="item.parseRun" class="stage-meta">
              {{ stateLabels[item.parseRun.status] || item.parseRun.status }}
              <span>解析版本：{{ item.parseRun.parseVersion }}</span>
            </p>
            <p v-else class="stage-hint">尚未解析，保存资料后可开始解析。</p>
            <p
              v-if="item.parseRun?.errorMessage"
              class="error-message"
              role="alert"
            >
              {{ item.parseRun.errorMessage }}
            </p>
            <p v-if="parsing" role="status">
              正在处理资料，请稍候。页面会自动更新解析状态。
            </p>
            <div class="stage-actions">
              <el-button
                v-if="(own || reviewer) && item.reviewState === 'DRAFT'"
                :loading="busy || parsing"
                :disabled="dirty"
                @click="parse"
                >{{
                  ["FAILED", "DEAD"].includes(item.parseRun?.status || "")
                    ? "重试解析"
                    : item.parseRun
                      ? "重新解析资料"
                      : "解析资料"
                }}</el-button><el-button @click="refresh"
                >刷新解析状态</el-button
              >
            </div></template
          >
        </section>
        <section class="stage-section">
          <h2>原文片段</h2>
          <p v-if="segmentError" class="error-message" role="alert">
            {{ segmentError }}
            <el-button @click="loadSegments()">重试加载片段</el-button>
          </p>
          <p v-if="!segments.length" class="stage-hint">
            {{
              parsing
                ? "解析完成后会在这里展示原文片段。"
                : "暂无已成功解析的片段。"
            }}
          </p>
          <ul class="stage-list">
            <li
              v-for="segment in segments"
              :id="`segment-${segment.id}`"
              :key="segment.id"
            >
              <div class="stage-meta">
                <span>片段 {{ segment.segmentNo }}</span
                ><span>{{ segment.pageLabel }}</span
                ><span>{{ segment.headingPath }}</span>
              </div>
              <label v-if="contributor" class="stage-inline-label"
                ><input
                  v-model="selected"
                  type="checkbox"
                  :value="segment.id"
                /><span
                  >选择片段 {{ segment.segmentNo }} 整理为知识草稿</span
                ></label
              >
              <p class="stage-prose">{{ segment.body }}</p>
            </li>
          </ul>
          <PaginationBar
            :page="segmentPage"
            :total="segmentTotal"
            @change="loadSegments"
          />
          <div v-if="segments.length && contributor" class="stage-toolbar">
            <label class="stage-field"
              >整理知识类型<select v-model="extractType">
                <option
                  v-for="(label, key) in typeLabels"
                  :key="key"
                  :value="key"
                >
                  {{ label }}
                </option>
              </select></label
            ><el-button
              :disabled="!selected.length"
              :loading="busy"
              @click="extract"
              >将 {{ selected.length }} 个片段整理为草稿</el-button
            >
            <p class="stage-hint">
              仅复制选定原文供人工整理，不会自动核实知识结论；创建后请逐项校正。
            </p>
          </div>
        </section>
        <div
          v-if="own && contributor"
          class="stage-actions stage-sticky-actions"
        >
          <el-button
            v-if="item.reviewState === 'DRAFT'"
            type="primary"
            :loading="busy"
            :disabled="
              dirty || (!!item.fileId && item.parseRun?.status !== 'SUCCEEDED')
            "
            @click="sourceAction('submit')"
            >提交来源审核</el-button
          ><el-button
            v-if="item.reviewState === 'IN_REVIEW'"
            :loading="busy"
            @click="sourceAction('withdraw')"
            >撤回来源审核</el-button
          ><el-button
            v-if="item.reviewState !== 'DRAFT'"
            :loading="busy"
            @click="sourceAction('revise')"
            >创建资料新稿</el-button
          >
          <p v-if="dirty" class="stage-hint">
            请先保存当前修改，再解析资料或提交审核。
          </p>
          <p
            v-if="
              item.reviewState === 'DRAFT' &&
              item.fileId &&
              item.parseRun?.status !== 'SUCCEEDED'
            "
            class="stage-hint"
          >
            有文件的资料需要成功解析并核对片段后，才能提交审核。
          </p>
          <p v-if="item.reviewState !== 'DRAFT'" class="stage-hint">
            本记录已冻结。需要修改时复制新稿，保留原资料的审核与解析历史。
          </p>
        </div>
        <details
          v-if="
            reviewer &&
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
              ></textarea></label
            ><el-button
              type="danger"
              native-type="submit"
              :loading="busy"
              :disabled="!reason.trim()"
              >停用来源并查看影响</el-button
            >
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
      ></WorkspaceState
    >
  </section>
</template>
