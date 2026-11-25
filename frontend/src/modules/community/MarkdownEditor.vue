<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import SourcePreview from './SourcePreview.vue';
import MarkdownToolIcon from './MarkdownToolIcon.vue';
import { applyMarkdown, type MarkdownAction } from './markdownEditing';
import type { Reference, SourceFormat, Syntax, TextPolicy } from './types';
const props = withDefaults(defineProps<{
  modelValue: string; id: string; sourceFormat: SourceFormat; policy: TextPolicy | null;
  references?: Reference[]; context: string; readonly?: boolean; describedby?: string;
  invalid?: boolean; kind?: 'ARTICLE' | 'POST';
}>(), { kind: 'ARTICLE' });
const emit = defineEmits<{ 'update:modelValue': [value: string] }>();
const mode = ref('edit'), input = ref<HTMLTextAreaElement>();
const tools: { action: MarkdownAction; label: string; syntax: Syntax }[] = [
  { action: 'heading', label: '标题', syntax: 'heading' }, { action: 'bold', label: '加粗', syntax: 'strong' },
  { action: 'italic', label: '斜体', syntax: 'emphasis' }, { action: 'ordered', label: '有序列表', syntax: 'list' },
  { action: 'unordered', label: '无序列表', syntax: 'list' }, { action: 'quote', label: '引用', syntax: 'blockquote' },
  { action: 'link', label: '链接', syntax: 'link' }, { action: 'code', label: '代码块', syntax: 'codeBlock' },
];
const permitted = (syntax: Syntax) => !props.readonly && props.sourceFormat === 'MARKDOWN' && !!props.policy?.config.allowedSyntax.includes(syntax);
async function resize() {
  await nextTick();
  const node = input.value;
  if (!node || mode.value === 'preview') return;
  node.style.height = 'auto';
  node.style.height = Math.max(node.scrollHeight, props.kind === 'POST' ? 180 : 420) + 'px';
}
async function insert(action: MarkdownAction) {
  const node = input.value;
  if (!node) return;
  const value = applyMarkdown(props.modelValue, node.selectionStart, node.selectionEnd, action);
  emit('update:modelValue', value.text);
  await nextTick();
  node.focus({ preventScroll: true });
  node.setSelectionRange(value.start, value.end);
}
function toolbarKeys(event: KeyboardEvent) {
  if (!['ArrowLeft','ArrowRight','Home','End'].includes(event.key)) return;
  const buttons = Array.from((event.currentTarget as HTMLElement).querySelectorAll<HTMLButtonElement>('button:not(:disabled)'));
  if (!buttons.length) return;
  const index = buttons.indexOf(document.activeElement as HTMLButtonElement);
  const next = event.key === 'Home' ? 0 : event.key === 'End' ? buttons.length - 1 : (index + (event.key === 'ArrowRight' ? 1 : -1) + buttons.length) % buttons.length;
  event.preventDefault(); buttons[next]?.focus();
}
watch(() => props.context, () => { mode.value = 'edit'; });
watch([() => props.modelValue, () => props.kind, mode], resize, { flush: 'post' });
onMounted(() => { void resize(); window.addEventListener('resize', resize); });
onBeforeUnmount(() => window.removeEventListener('resize', resize));
</script>
<template>
  <div class="markdown-editor" :class="['markdown-mode-' + mode, 'markdown-kind-' + kind.toLowerCase()]">
    <div class="markdown-editor-chrome">
      <div class="markdown-mode-tabs" role="group" aria-label="正文视图">
        <button type="button" :aria-pressed="mode === 'edit'" @click="mode = 'edit'">编辑</button>
        <button type="button" class="markdown-split-toggle" :aria-pressed="mode === 'split'" @click="mode = 'split'">分屏</button>
        <button type="button" :aria-pressed="mode === 'preview'" @click="mode = 'preview'">预览正文</button>
      </div>
      <div v-if="mode !== 'preview' && sourceFormat === 'MARKDOWN'" class="markdown-toolbar" role="toolbar" aria-label="Markdown格式工具" @keydown="toolbarKeys">
        <button v-for="tool in tools" :key="tool.action" type="button" :aria-label="tool.label" :title="permitted(tool.syntax) ? tool.label : tool.label + '：当前排版规则或权限不可用'" :disabled="!permitted(tool.syntax)" @mousedown.prevent @click="insert(tool.action)"><MarkdownToolIcon :action="tool.action" /></button>
      </div>
    </div>
    <div class="markdown-workspace">
      <div v-show="mode !== 'preview'" class="markdown-input-pane">
        <label :for="id" class="community-field editor-body-label">正文（必填）</label>
        <textarea :id="id" ref="input" :value="modelValue" :rows="kind === 'POST' ? 5 : 12" :required="mode !== 'preview'" :readonly="readonly" :aria-describedby="describedby" :aria-invalid="invalid" :placeholder="kind === 'POST' ? '写下你的问题、已有思考，或希望交流的想法…' : '从一个观点开始，写下正文。支持 Markdown 排版，也可用 [编号] 引用文献。'" @input="emit('update:modelValue', ($event.target as HTMLTextAreaElement).value)" />
      </div>
      <SourcePreview v-if="mode !== 'edit'" :body="modelValue" :source-format="sourceFormat" :references="references" :policy="policy" :context="context" />
    </div>
  </div>
</template>
