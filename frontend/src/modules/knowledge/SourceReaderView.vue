<script setup lang="ts">
import {
  computed,
  defineAsyncComponent,
  onBeforeUnmount,
  onMounted,
  ref,
  watch,
  nextTick,
} from "vue";
import { onBeforeRouteLeave, useRoute, useRouter } from "vue-router";
import { useSessionStore } from "../../shared/stores/session";
import { errorMessage } from "../../shared/api/client";
import {
  stageApi,
  type Category,
  type KnowledgeTag,
  type KnowledgeInput,
  type KnowledgeDetail,
} from "./api";
import {
  readerApi,
  type Reader,
  type ReadingUnit,
  type ReadingMode,
  type Excerpt,
  type ReaderHit,
  type ExcerptRange,
  type KnowledgeMatch,
  type KnowledgeProposal,
} from "./readerApi";
import { blankKnowledge, knowledgePayload, validateKnowledge } from "./editor";
import { canonicalSelection } from "./readerSelection";
import KnowledgeDraftForm from "./KnowledgeDraftForm.vue";
import ReaderAst from "./ReaderAst.vue";
const ReaderPdf = defineAsyncComponent(() => import("./ReaderPdf.vue"));
import ReaderOutline from "./ReaderOutline.vue";
import "./stage.css";
import "./reader.css";
import {
  availableModes,
  canExtract,
  canSearchBook,
  initialMode,
  warningText,
  modeLabels,
} from "./readerModes";
const route = useRoute(),
  router = useRouter(),
  session = useSessionStore();
const directoryPanel = ref<HTMLElement>(),
  workspacePanel = ref<HTMLElement>(),
  directoryToggle = ref<HTMLButtonElement>(),
  workspaceToggle = ref<HTMLButtonElement>(),
  mobile = ref(false),
  position = ref<{ blockId?: string; offset?: number }>({});
function panelKeys(event: KeyboardEvent, kind: "directory" | "workspace") {
  if (!mobile.value) return;
  const panel =
    kind === "directory" ? directoryPanel.value : workspacePanel.value;
  const toggle =
    kind === "directory" ? directoryToggle.value : workspaceToggle.value;
  if (event.key === "Escape") {
    event.preventDefault();
    if (kind === "directory") directory.value = false;
    else workspace.value = false;
    toggle?.focus();
    return;
  }
  if (event.key === "Tab") {
    const buttons = panel?.querySelectorAll<HTMLElement>(
      "button:not(:disabled),input:not(:disabled),select:not(:disabled),textarea:not(:disabled),a[href]",
    );
    if (!buttons?.length) return;
    const first = buttons[0]!,
      last = buttons[buttons.length - 1]!;
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  }
}
function viewport() {
  mobile.value = window.innerWidth < 768;
}
function recordPosition() {
  if (!unit.value || loading.value || !root.value) return;
  const blocks = [
    ...root.value.querySelectorAll<HTMLElement>("[data-block-id]"),
  ];
  const target = blocks.find((b) => b.getBoundingClientRect().bottom > 120);
  if (target) {
    position.value = { blockId: target.dataset.blockId, offset: 0 };
    scheduleProgress();
  }
}
onMounted(() => {
  viewport();
  window.addEventListener("resize", viewport);
  window.addEventListener("scroll", recordPosition, { passive: true });
});
const reader = ref<Reader | null>(null),
  unit = ref<ReadingUnit | null>(null),
  unitId = ref(""),
  error = ref(""),
  message = ref(""),
  busy = ref(false),
  loading = ref(false),
  directory = ref(false),
  workspace = ref(false),
  readingMode = ref<ReadingMode>("STRUCTURED"),
  rawPdf = ref(false),
  fontSize = ref(18),
  scale = ref(1),
  q = ref(""),
  hits = ref<ReaderHit[]>([]),
  hitPage = ref(1),
  hitTotal = ref(0),
  searching = ref(false),
  root = ref<HTMLElement>(),
  selection = ref<{ ranges: ExcerptRange[]; quoteText: string } | null>(null),
  popup = ref({ x: 0, y: 0 }),
  assets = ref<Record<string, string>>({}),
  form = ref<KnowledgeInput>(blankKnowledge()),
  categories = ref<Category[]>([]),
  tags = ref<KnowledgeTag[]>([]),
  matches = ref<KnowledgeMatch[]>([]),
  draft = ref<KnowledgeDetail | null>(null),
  proposal = ref<KnowledgeProposal | null>(null),
  snapshot = ref(""),
  validation = ref<string[]>([]),
  matchError = ref(""),
  ownDrafts = ref<KnowledgeDetail[]>([]),
  otherDraft = ref(""),
  savedExcerpt = ref<Excerpt | null>(null),
  showDraftPicker = ref(false),
  draftPage = ref(1),
  draftTotal = ref(0);
let searchEpoch = 0;
let epoch = 0,
  unitEpoch = 0,
  progressTimer: ReturnType<typeof setTimeout> | undefined,
  matchTimer: ReturnType<typeof setTimeout> | undefined,
  matchEpoch = 0;
const urls: string[] = [];
let progressVersion: number | undefined,
  progressBlocked = false,
  progressQueue = Promise.resolve();
const modes = computed(() =>
    unit.value
      ? availableModes(
          unit.value,
          reader.value?.capabilities.download || false,
          reader.value?.edition.format || "",
        )
      : [],
  ),
  isPreview = computed(() =>
    ["ORIGINAL_PREVIEW", "FIXED_LAYOUT"].includes(readingMode.value),
  ),
  searchable = computed(() => canSearchBook(reader.value?.units || []));
const id = computed(() => String(route.params.id)),
  pdf = computed(() => reader.value?.edition.format.toUpperCase() === "PDF"),
  canEdit = computed(
    () => !!session.user?.permissions.includes("contribution:submit"),
  ),
  dirty = computed(
    () =>
      JSON.stringify(form.value) !== snapshot.value &&
      !!(form.value.title || form.value.body || form.value.evidence.length),
  ),
  editable = computed(() =>
    proposal.value
      ? proposal.value.status === "DRAFT"
      : !draft.value || draft.value.revisionState === "DRAFT",
  );
function changeMode() {
  rawPdf.value = false;
  void openUnit(unitId.value);
}
async function download() {
  try {
    const blob = await readerApi.blob(id.value);
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = reader.value?.document.fileName || "source";
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  } catch (e) {
    error.value = errorMessage(e);
  }
}
function clearUrls() {
  urls.splice(0).forEach(URL.revokeObjectURL);
  assets.value = {};
}
async function load() {
  const ticket = ++epoch;
  ++unitEpoch;
  clearTimeout(progressTimer);
  clearTimeout(matchTimer);
  progressBlocked = false;
  matchEpoch++;
  searchEpoch++;
  q.value = "";
  hits.value = [];
  hitTotal.value = 0;
  hitPage.value = 1;
  ownDrafts.value = [];
  otherDraft.value = "";
  message.value = "";
  matchError.value = "";
  validation.value = [];
  showDraftPicker.value = false;
  savedExcerpt.value = null;
  clearUrls();
  reader.value = null;
  unit.value = null;
  rawPdf.value = false;
  selection.value = null;
  workspace.value = false;
  form.value = blankKnowledge();
  snapshot.value = JSON.stringify(form.value);
  draft.value = null;
  proposal.value = null;
  matches.value = [];
  error.value = "";
  loading.value = true;
  try {
    const value = await readerApi.reader(
      id.value,
      typeof route.query.editionId === "string"
        ? route.query.editionId
        : undefined,
    );
    if (ticket !== epoch) return;
    reader.value = value;
    progressVersion = value.progress?.lockVersion;
    fontSize.value = value.progress?.fontSize || 18;
    readingMode.value = value.progress?.readingMode || "STRUCTURED";
    const chosen =
      typeof route.query.unitId === "string"
        ? route.query.unitId
        : value.progress?.unitId;
    unitId.value = value.units.some((c) => c.id === chosen)
      ? chosen!
      : value.units[0]?.id || "";
    await openUnit(unitId.value, false);
    if (ticket !== epoch) return;
    const taxonomy = await Promise.all([
      stageApi.categories(),
      stageApi.tags(),
    ]);
    if (ticket !== epoch) return;
    [categories.value, tags.value] = taxonomy;
  } catch (e) {
    if (ticket === epoch) error.value = errorMessage(e);
  } finally {
    if (ticket === epoch) loading.value = false;
  }
}
async function loadUnitAssets(value: ReadingUnit, life: number) {
  if (!reader.value) return;
  const refs = new Set<string>();
  if (value.previewRef) refs.add(value.previewRef);
  function visit(nodes: import("./readerApi").ReaderNode[]) {
    for (const node of nodes) {
      if (node.assetRef) refs.add(node.assetRef);
      if (node.children) visit(node.children);
    }
  }
  visit(value.nodes);
  await Promise.all(
    [...refs].map(async (ref) => {
      if (assets.value[ref]) return;
      const asset = reader.value?.assets.find((a) => a.ref === ref);
      if (
        !asset ||
        !["IMAGE", "PAGE_PREVIEW"].includes(asset.kind) ||
        !/^image\/(png|jpeg)$/.test(asset.mimeType)
      )
        return;
      try {
        const blob = await readerApi.blob(id.value, asset.id);
        if (life !== epoch) return;
        const url = URL.createObjectURL(blob);
        urls.push(url);
        assets.value[ref] = url;
      } catch (e) {
        if (life === epoch)
          error.value = `插图或预览读取失败：${errorMessage(e)}`;
      }
    }),
  );
}
async function openUnit(
  value: string,
  save = true,
  blockId?: string,
  start?: number,
  end?: number,
  localId?: string,
) {
  if (!reader.value || !value) return;
  const summary = reader.value.units.find((u) => u.id === value);
  if (summary)
    readingMode.value = initialMode(
      summary.availableReadingModes,
      readingMode.value,
    );
  if (!pdf.value || !reader.value.capabilities.download || blockId || localId)
    rawPdf.value = false;
  const ticket = ++unitEpoch,
    life = epoch;
  selection.value = null;
  savedExcerpt.value = null;
  error.value = "";
  try {
    const result = await readerApi.unit(
      id.value,
      value,
      reader.value.edition.id,
      readingMode.value,
    );
    if (ticket !== unitEpoch || life !== epoch) return;
    unit.value = result;
    unitId.value = value;
    position.value = {};
    await loadUnitAssets(result, life);
    if (ticket !== unitEpoch || life !== epoch) return;
    directory.value = false;
    await nextTick();
    const block =
      blockId ||
      result.blocks.find((b) => b.localId === localId)?.id ||
      (typeof route.query.blockId === "string"
        ? route.query.blockId
        : !save
          ? reader.value.progress?.blockId
          : undefined);
    if (block) {
      const element = document.getElementById(`block-${block}`);
      element?.scrollIntoView?.({ block: "center" });
      if (element) {
        const from =
            start ??
            Number(route.query.start || reader.value.progress?.offset || 0),
          to = end ?? Number(route.query.end || from);
        highlight(element, from, to);
      }
    }
    if (
      !save &&
      typeof route.query.ranges === "string" &&
      route.query.ranges.length < 16000
    ) {
      try {
        const ranges: unknown = JSON.parse(route.query.ranges);
        if (Array.isArray(ranges))
          for (const entry of ranges.slice(0, 100)) {
            if (
              entry &&
              typeof entry.blockId === "string" &&
              Number.isInteger(entry.start) &&
              Number.isInteger(entry.end)
            ) {
              const block = result.blocks.find((b) => b.id === entry.blockId);
              const element = document.getElementById(`block-${entry.blockId}`);
              if (
                block &&
                element &&
                entry.start >= 0 &&
                entry.end <= Array.from(block.text).length
              )
                highlight(element, entry.start, entry.end);
            }
          }
      } catch {
        /* malformed client locator never alters canonical source */
      }
    }
    if (save) scheduleProgress();
  } catch (e) {
    if (ticket === unitEpoch && life === epoch) error.value = errorMessage(e);
  }
}
function highlight(element: HTMLElement, start: number, end: number) {
  const walker = document.createTreeWalker(element, NodeFilter.SHOW_TEXT);
  let node: Node | null,
    offset = 0,
    begin: Node | undefined,
    finish: Node | undefined,
    a = 0,
    b = 0;
  while ((node = walker.nextNode())) {
    const text = node.textContent || "",
      size = Array.from(text).length;
    if (!begin && start <= offset + size) {
      begin = node;
      a = Array.from(text)
        .slice(0, Math.max(0, start - offset))
        .join("").length;
    }
    if (end <= offset + size) {
      finish = node;
      b = Array.from(text)
        .slice(0, Math.max(0, end - offset))
        .join("").length;
      break;
    }
    offset += size;
  }
  if (begin && finish && end > start) {
    const range = document.createRange();
    range.setStart(begin, a);
    range.setEnd(finish, b);
    const mark = document.createElement("mark");
    try {
      range.surroundContents(mark);
    } catch {
      /* Inline boundaries: show exact browser selection without modifying AST. */ const s =
        window.getSelection();
      s?.removeAllRanges();
      s?.addRange(range);
    }
  }
}
function scheduleProgress() {
  clearTimeout(progressTimer);
  if (progressBlocked) return;
  const life = epoch;
  progressTimer = setTimeout(() => {
    progressQueue = progressQueue.then(async () => {
      if (life !== epoch || !reader.value || !unit.value || progressBlocked)
        return;
      try {
        const result = await readerApi.progress(id.value, {
          editionId: reader.value.edition.id,
          unitId: unit.value.id,
          ...position.value,
          pageNumber: unit.value.pageNumber,
          fontSize: fontSize.value,
          readingMode: readingMode.value,
          expectedVersion: progressVersion,
        });
        if (life === epoch) progressVersion = result.lockVersion;
      } catch (e) {
        if (life === epoch) {
          progressBlocked = true;
          error.value = `阅读进度未保存：${errorMessage(e)}。请刷新恢复服务器进度。`;
        }
      }
    });
  }, 800);
}
function capture() {
  savedExcerpt.value = null;
  if (
    !reader.value ||
    !unit.value ||
    !canExtract(unit.value, reader.value.capabilities.extract) ||
    !root.value
  )
    return;
  try {
    const s = window.getSelection();
    if (
      pdf.value &&
      rawPdf.value &&
      s?.rangeCount &&
      !s.isCollapsed &&
      root.value.contains(s.anchorNode)
    ) {
      const text = s.toString();
      const candidates = unit.value.blocks.flatMap((b) => {
        const first = b.text.indexOf(text);
        return first >= 0 && b.text.indexOf(text, first + 1) < 0
          ? [{ b, first }]
          : [];
      });
      if (candidates.length !== 1) {
        selection.value = null;
        error.value =
          "PDF 文字层与规范文本无法唯一对齐，请打开本页规范文本后重新选择";
        return;
      }
      const { b, first } = candidates[0]!;
      const start = Array.from(b.text.slice(0, first)).length;
      selection.value = {
        ranges: [
          { blockId: b.id, start, end: start + Array.from(text).length },
        ],
        quoteText: text,
      };
    } else
      selection.value = canonicalSelection(root.value, unit.value.blocks, s);
    if (selection.value && s?.rangeCount) {
      const rect = s.getRangeAt(0).getBoundingClientRect?.() || {
        left: 8,
        top: 60,
      };
      popup.value = {
        x: Math.max(8, Math.min(window.innerWidth-Math.min(720,window.innerWidth-16)-8,rect.left)),
        y: Math.max(8, rect.top - (mobile.value?120:64)),
      };
    }
  } catch (e) {
    selection.value = null;
    error.value = errorMessage(e);
  }
}
async function copy() {
  try {
    await navigator.clipboard.writeText(selection.value?.quoteText || "");
    message.value = "已复制选文";
  } catch (e) {
    error.value = errorMessage(e);
  }
}
async function persistSelection() {
  if (savedExcerpt.value) return savedExcerpt.value;
  if (!selection.value || !reader.value) throw new Error("请先选择可靠原文");
  const life = epoch;
  const selected = selection.value;
  const saved = await readerApi.excerpt(
    id.value,
    reader.value.edition.id,
    selected.ranges,
    selected.quoteText,
  );
  if (life !== epoch)
    throw new Error("阅读资料或账号已变化，摘录保留在原账号中");
  savedExcerpt.value = saved;
  return saved;
}
async function organize(
  target: "current" | "new" | "personal" | "other" = "current",
) {
  const life = epoch;
  busy.value = true;
  error.value = "";
  try {
    if (
      target === "new" &&
      dirty.value &&
      !window.confirm("另建知识草稿会替换工作区未保存内容，是否继续？")
    )
      return;
    const saved = await persistSelection();
    if (life !== epoch) return;
    if (target === "personal") {
      message.value = "个人摘录已保存，可在任意知识草稿的来源依据中选用";
      selection.value = null;
      return;
    }
    if (target === "other") {
      showDraftPicker.value = true;
      await loadOwnDrafts();
      return;
    }
    if (target === "new") {
      draft.value = null;
      proposal.value = null;
      form.value = blankKnowledge();
      snapshot.value = JSON.stringify(form.value);
    }
    if (!editable.value)
      throw new Error("当前稿件已冻结，摘录已保留，请选择其他可编辑草稿");
    if (!form.value.evidence.some((e) => e.excerptId === saved.id))
      form.value.evidence.push({
        ...saved,
        id: undefined,
        excerptId: saved.id,
      });
    workspace.value = true;
    message.value = "摘录已保存，请补充知识名称、正文与事实";
    selection.value = null;
  } catch (e) {
    if (life === epoch)
      error.value = `${savedExcerpt.value ? "摘录已保存，后续操作失败" : "摘录未保存"}：${errorMessage(e)}。当前选文保留，可重试。`;
  } finally {
    if (life === epoch) busy.value = false;
  }
}
async function loadOwnDrafts(page = 1) {
  const result = await stageApi.drafts({ state: "DRAFT", page, pageSize: 20 });
  ownDrafts.value = result.items.filter(
    (d) => d.ownerId === session.user?.id && d.revisionState === "DRAFT",
  );
  draftTotal.value = result.total;
  draftPage.value = page;
}
async function appendToOther() {
  if (!otherDraft.value || !savedExcerpt.value) return;
  busy.value = true;
  try {
    const current = await stageApi.draft(otherDraft.value);
    if (
      current.ownerId !== session.user?.id ||
      current.revisionState !== "DRAFT"
    )
      throw new Error("该稿件已经无法编辑，摘录仍保存在个人摘录中");
    await readerApi.appendEvidence(
      current.id,
      [savedExcerpt.value.id],
      current.lockVersion,
    );
    message.value = "已加入选定草稿，当前知识工作区保持不变";
    showDraftPicker.value = false;
    selection.value = null;
  } catch (e) {
    error.value = `加入草稿失败：${errorMessage(e)}。摘录已安全保留。`;
  } finally {
    busy.value = false;
  }
}
async function search(page = 1) {
  if (!reader.value || !searchable.value || !q.value.trim()) return;
  const life = epoch;
  const request = ++searchEpoch;
  searching.value = true;
  error.value = "";
  try {
    const result = await readerApi.search(
      id.value,
      reader.value.edition.id,
      q.value,
      page,
    );
    if (life !== epoch || request !== searchEpoch) return;
    hits.value = result.items;
    hitTotal.value = result.total;
    hitPage.value = page;
  } catch (e) {
    if (life === epoch && request === searchEpoch)
      error.value = errorMessage(e);
  } finally {
    if (life === epoch && request === searchEpoch) searching.value = false;
  }
}
function applyKnowledge(value: KnowledgeInput) {
  form.value = {
    ...blankKnowledge(value.type),
    ...JSON.parse(JSON.stringify(value)),
    categoryId: value.categoryId || undefined,
  };
  snapshot.value = JSON.stringify(form.value);
}
async function useMatch(match: KnowledgeMatch) {
  busy.value = true;
  error.value = "";
  const evidence = [...form.value.evidence];
  try {
    if (match.ownerId === session.user?.id) {
      const value =
        match.revisionState === "DRAFT"
          ? await stageApi.draft(match.id)
          : await stageApi.revise(match.id);
      draft.value = value;
      proposal.value = null;
      applyKnowledge({
        ...value,
        categoryId: value.categoryId || undefined,
        tagIds: value.tags?.map((t) => t.id) || [],
      });
    } else {
      const value = await stageApi.detail(match.id);
      const knowledge = {
        ...knowledgePayload({
          ...value,
          categoryId: value.categoryId || undefined,
        }),
        tagIds: value.tags?.map((t) => t.id) || [],
      };
      const created = await readerApi.createProposal(
        match.id,
        match.revisionId,
        knowledge,
      );
      proposal.value = created;
      draft.value = null;
      applyKnowledge(created.knowledge);
    }
    for (const row of evidence)
      if (
        !form.value.evidence.some(
          (e) => e.excerptId && e.excerptId === row.excerptId,
        )
      )
        form.value.evidence.push(row);
  } catch (e) {
    error.value = errorMessage(e);
  } finally {
    busy.value = false;
  }
}
async function saveDraft() {
  const life = epoch;
  const input = knowledgePayload(form.value);
  validation.value = validateKnowledge(input);
  if (validation.value.length) return;
  busy.value = true;
  error.value = "";
  try {
    if (proposal.value) {
      const savedProposal = await readerApi.saveProposal(
        proposal.value.id,
        proposal.value.lockVersion,
        input,
      );
      if (life !== epoch) return;
      proposal.value = savedProposal;
      applyKnowledge(proposal.value.knowledge);
    } else {
      const saved = await stageApi.saveDraft(draft.value?.id || null, {
        ...input,
        ...(draft.value ? { expectedVersion: draft.value.lockVersion } : {}),
      });
      if (life !== epoch) return;
      draft.value = saved;
      applyKnowledge({
        ...saved,
        categoryId: saved.categoryId || undefined,
        tagIds: saved.tags?.map((t) => t.id) || [],
      });
    }
    message.value = proposal.value ? "修订建议草稿已保存" : "知识草稿已保存";
  } catch (e) {
    error.value = errorMessage(e);
  } finally {
    busy.value = false;
  }
}
async function submit() {
  if (dirty.value) {
    error.value = "请先保存当前修改";
    return;
  }
  busy.value = true;
  try {
    if (proposal.value)
      proposal.value = await readerApi.proposalAction(
        proposal.value.id,
        "submit",
      );
    else if (draft.value)
      draft.value = await stageApi.revisionAction(
        draft.value.revisionId,
        "submit",
      );
    message.value = "已提交审核";
  } catch (e) {
    error.value = errorMessage(e);
  } finally {
    busy.value = false;
  }
}
watch(
  () => [form.value.canonicalName, form.value.type],
  () => {
    clearTimeout(matchTimer);
    const ticket = ++matchEpoch;
    matches.value = [];
    if (!form.value.canonicalName.trim()) return;
    matchTimer = setTimeout(async () => {
      try {
        const result = await readerApi.matches(
          form.value.canonicalName,
          form.value.type,
        );
        if (ticket === matchEpoch) {
          matches.value = result;
          matchError.value = "";
        }
      } catch (e) {
        if (ticket === matchEpoch) matchError.value = errorMessage(e);
      }
    }, 400);
  },
);
watch(() => [route.params.id, route.query.editionId, session.user?.id], load, {
  immediate: true,
});
watch(fontSize, scheduleProgress);
watch(directory, async (opened) => {
  if (opened && mobile.value) {
    await nextTick();
    directoryPanel.value?.querySelector<HTMLElement>("button")?.focus();
  }
});
watch(workspace, async (opened) => {
  if (opened && mobile.value) {
    await nextTick();
    workspacePanel.value?.querySelector<HTMLElement>("button")?.focus();
  }
});
onBeforeUnmount(() => {
  window.removeEventListener("resize", viewport);
  window.removeEventListener("scroll", recordPosition);
  epoch++;
  matchEpoch++;
  clearTimeout(progressTimer);
  clearTimeout(matchTimer);
  clearUrls();
});
onBeforeRouteLeave(
  () => !dirty.value || window.confirm("知识工作区有未保存修改，确定离开吗？"),
);
</script>
<template>
  <section class="stage-page source-workflow source-reader">
    <header class="reader-header">
      <RouterLink :to="`/sources/${id}`">返回书目</RouterLink>
      <h1>{{ reader?.document.title || "来源阅读" }}</h1>
      <button
        ref="directoryToggle"
        @click="directory = !directory"
        :aria-expanded="directory"
      >
        目录与搜索</button
      ><button
        ref="workspaceToggle"
        v-if="canEdit"
        @click="workspace = !workspace"
        :aria-expanded="workspace"
      >
        知识工作区（{{ form.evidence.length }}）
      </button>
    </header>
    <p v-if="error" role="alert" class="error-message">{{ error }}</p>
    <p v-if="message" role="status">{{ message }}</p>
    <p v-if="loading" role="status">正在加载阅读版本…</p>
    <template v-if="reader"
      ><p class="stage-hint">
        {{ reader.edition.parseVersion }} · {{ reader.edition.format
        }}<span v-if="reader.document.readingEditionId !== reader.edition.id">
          · 当前为历史引用版本</span
        >
      </p>
      <p
        v-for="warning in reader.edition.warnings"
        :key="String(typeof warning === 'string' ? warning : warning.code)"
        class="stage-file-note"
      >
        {{ warningText(warning) }}
      </p>
      <div class="reader-layout" :class="{ 'has-workspace': workspace }">
        <aside
          ref="directoryPanel"
          :role="mobile ? 'dialog' : undefined"
          :aria-modal="mobile && directory ? true : undefined"
          aria-label="目录与全文搜索"
          class="reader-directory"
          :class="{ open: directory }"
          @keydown="panelKeys($event, 'directory')"
        >
          <header>
            <h2>目录</h2>
            <button class="reader-mobile-close" @click="directory = false">
              关闭目录
            </button>
          </header>
          <ReaderOutline
            :items="reader.outline"
            @choose="
              (n, local) => {
                const c = reader?.units.find((c) => c.unitNo === n);
                if (c)
                  openUnit(c.id, true, undefined, undefined, undefined, local);
              }
            "
          /><label class="stage-field"
            >阅读单元<select v-model="unitId" @change="openUnit(unitId)">
              <option v-for="c in reader.units" :key="c.id" :value="c.id">
                {{ c.pageNumber ? `第 ${c.pageNumber} 页 · ` : ""
                }}{{ c.title }}
              </option>
            </select></label
          >
          <p v-if="!searchable" class="stage-hint">
            本书当前没有可靠可搜索文字。
          </p>
          <form v-else @submit.prevent="search()">
            <label class="stage-field"
              >在本书中搜索<input
                v-model="q"
                maxlength="200"
                type="search"
                required /></label
            ><button :disabled="searching">搜索全文</button>
          </form>
          <p v-if="searchable" class="stage-hint">
            全文搜索仅覆盖可靠文字单元
            {{ reader.units.filter((u) => u.capabilities.searchable).length }} /
            {{ reader.units.length }}。
          </p>
          <p v-if="hitTotal">共 {{ hitTotal }} 处匹配</p>
          <ol class="reader-hits">
            <li
              v-for="(hit, index) in hits"
              :key="`${hit.blockId}-${hit.offset}-${index}`"
            >
              <button
                @click="
                  readingMode = 'PLAIN_TEXT';
                  openUnit(
                    hit.unitId,
                    true,
                    hit.blockId,
                    hit.offset,
                    hit.endOffset,
                  );
                "
              >
                {{ hit.text }}
              </button>
            </li>
          </ol>
          <div v-if="hitTotal > 20" class="stage-actions">
            <button
              :disabled="hitPage <= 1 || searching"
              @click="search(hitPage - 1)"
            >
              上一页结果</button
            ><button
              :disabled="hitPage * 20 >= hitTotal || searching"
              @click="search(hitPage + 1)"
            >
              下一页结果
            </button>
          </div>
        </aside>
        <main class="reader-main">
          <div class="reader-tools">
            <label
              v-if="
                unit?.capabilities.fontSizeAdjustable &&
                readingMode !== 'ORIGINAL_PREVIEW' &&
                readingMode !== 'FIXED_LAYOUT'
              "
              >正文字号<select v-model.number="fontSize">
                <option v-for="n in [16, 18, 20, 22, 24]" :key="n" :value="n">
                  {{ n }}px
                </option>
              </select></label
            ><label
              >阅读排版<select
                :disabled="!modes.length"
                v-model="readingMode"
                @change="changeMode"
              >
                <option v-for="mode in modes" :key="mode" :value="mode">
                  {{ modeLabels[mode] }}
                </option>
              </select></label
            ><label v-if="pdf && reader.capabilities.download"
              ><input v-model="rawPdf" type="checkbox" />原始 PDF 页面</label
            ><label v-if="rawPdf || isPreview"
              >预览缩放<select v-model.number="scale">
                <option
                  v-for="n in [0.75, 1, 1.25, 1.5, 2]"
                  :key="n"
                  :value="n"
                >
                  {{ n * 100 }}%
                </option>
              </select></label
            ><button v-if="reader.capabilities.download" @click="download">
              下载原文件
            </button>
          </div>
          <div
            ref="root"
            class="reader-content"
            :style="{ fontSize: `${fontSize}px` }"
            @pointerup="capture"
            @keyup.shift="capture"
          >
            <template v-if="unit"
              ><h2>{{ unit.title }}</h2>
              <p
                v-if="!canExtract(unit, reader.capabilities.extract)"
                class="stage-hint"
              >
                当前单元不支持可靠文字引用，仅可阅读已提供的内容。
              </p>
              <p
                v-for="(warning, index) in unit.warnings || []"
                :key="index"
                class="stage-file-note"
              >
                {{ warningText(warning) }}
              </p>
              <p v-if="!unit.availableReadingModes.length" role="status">
                当前单元未生成可用的安全阅读模式，请查看处理警告或稍后重试。
              </p>
              <ReaderPdf
                v-else-if="pdf && rawPdf && reader.capabilities.download"
                :key="`${id}:${reader.edition.id}`"
                :document-id="id"
                :page-number="unit.pageNumber || unit.unitNo"
                :scale="scale" />
              <div v-else-if="readingMode === 'PLAIN_TEXT'">
                <p
                  v-for="block in unit.blocks"
                  :id="`block-${block.id}`"
                  :key="block.id"
                  :data-block-id="block.id"
                >
                  {{ block.text }}
                </p>
              </div>
              <div v-else-if="isPreview" class="reader-preview">
                <img
                  v-if="unit.previewRef && assets[unit.previewRef]"
                  :src="assets[unit.previewRef]"
                  :alt="`${unit.title}安全页面预览`"
                  :style="{ width: `${scale * 100}%`, maxWidth: 'none' }"
                />
                <p v-else role="status">
                  当前单元的安全预览尚不可用，不会回退交付原文件。
                </p>
              </div>
              <ReaderAst
                v-else
                :nodes="unit.nodes"
                :blocks="unit.blocks"
                :assets="assets"
            /></template>
          </div>
          <nav class="stage-actions" aria-label="阅读翻章">
            <button
              :disabled="reader.units.findIndex((c) => c.id === unitId) <= 0"
              @click="
                openUnit(
                  reader.units[
                    reader.units.findIndex((c) => c.id === unitId) - 1
                  ]?.id || '',
                )
              "
            >
              上一个单元</button
            ><button
              :disabled="
                reader.units.findIndex((c) => c.id === unitId) >=
                reader.units.length - 1
              "
              @click="
                openUnit(
                  reader.units[
                    reader.units.findIndex((c) => c.id === unitId) + 1
                  ]?.id || '',
                )
              "
            >
              下一个单元
            </button>
          </nav>
        </main>
        <aside
          v-if="workspace"
          ref="workspacePanel"
          :role="mobile ? 'dialog' : undefined"
          :aria-modal="mobile ? true : undefined"
          aria-label="知识整理工作区"
          class="reader-workspace"
          @keydown="panelKeys($event, 'workspace')"
        >
          <header class="stage-row-header">
            <h2>
              {{
                proposal
                  ? "协作修订建议"
                  : draft
                    ? "继续知识草稿"
                    : "整理为知识"
              }}
            </h2>
            <button @click="workspace = false">收起</button>
          </header>
          <p>引文作为依据保存，请独立整理知识正文。</p>
          <p v-if="matchError" role="alert">匹配查询失败：{{ matchError }}</p>
          <ul v-if="matches.length" class="reader-matches">
            <li v-for="match in matches" :key="match.id">
              <span>{{ match.title }} · {{ match.canonicalName }}</span
              ><button :disabled="busy" @click="useMatch(match)">
                {{
                  match.ownerId === session.user?.id
                    ? "继续本人草稿 / 修订"
                    : "提出独立修订建议"
                }}
              </button>
            </li>
          </ul>
          <p v-if="validation.length" role="alert" class="error-message">
            {{ validation.join("；") }}
          </p>
          <KnowledgeDraftForm
            v-model="form"
            :categories="categories"
            :tags="tags"
            :editable="editable"
            :is-new="!draft && !proposal"
          />
          <div class="stage-actions">
            <button
              v-if="editable"
              :disabled="busy || !canEdit"
              @click="saveDraft"
            >
              {{ proposal ? "保存修订建议" : "保存知识草稿" }}</button
            ><button
              v-if="(draft || proposal) && editable"
              :disabled="busy || dirty"
              @click="submit"
            >
              提交审核</button
            ><RouterLink
              v-if="proposal"
              :to="`/contribute/proposals/${proposal.id}`"
              >打开提案详情</RouterLink
            ><RouterLink
              v-else-if="draft"
              :to="`/contribute/knowledge/${draft.id}`"
              >打开稿件详情</RouterLink
            >
          </div>
        </aside>
      </div>
      <div
        v-if="selection"
        class="reader-selection"
        :style="{ left: `${popup.x}px`, top: `${popup.y}px` }"
        role="toolbar"
        aria-label="选文操作"
        @pointerdown.prevent
      >
        <button @click="copy">复制</button
        ><button
          v-if="canEdit && editable"
          :disabled="busy"
          @click="organize('current')"
        >
          {{
            busy
              ? "正在保存摘录"
              : draft || proposal || form.evidence.length
                ? "加入当前稿"
                : "整理为知识"
          }}</button
        ><button v-if="canEdit" :disabled="busy" @click="organize('new')">
          新建知识</button
        ><button v-if="canEdit" :disabled="busy" @click="organize('other')">
          加入其他草稿</button
        ><button v-if="canEdit" :disabled="busy" @click="organize('personal')">
          仅存个人摘录</button
        ><button @click="selection = null">关闭</button>
      </div>
      <section
        v-if="showDraftPicker"
        class="reader-draft-picker"
        aria-label="选择本人草稿"
      >
        <h2>加入本人可编辑草稿</h2>
        <p>摘录已保存；加入失败不会丢失。不会切换当前整理稿。</p>
        <label class="stage-field"
          >目标稿件<select v-model="otherDraft">
            <option value="">请选择</option>
            <option v-for="d in ownDrafts" :key="d.id" :value="d.id">
              {{ d.title }}
            </option>
          </select></label
        >
        <p v-if="!ownDrafts.length">此页没有本人可编辑草稿。</p>
        <div class="stage-actions">
          <button
            :disabled="draftPage <= 1 || busy"
            @click="
              loadOwnDrafts(draftPage - 1).catch(
                (e) => (error = errorMessage(e)),
              )
            "
          >
            上一页</button
          ><button
            :disabled="draftPage * 20 >= draftTotal || busy"
            @click="
              loadOwnDrafts(draftPage + 1).catch(
                (e) => (error = errorMessage(e)),
              )
            "
          >
            下一页</button
          ><button :disabled="!otherDraft || busy" @click="appendToOther">
            确认加入</button
          ><button @click="showDraftPicker = false">关闭</button>
        </div>
      </section></template
    >
  </section>
</template>
