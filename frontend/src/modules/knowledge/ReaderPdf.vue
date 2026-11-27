<script setup lang="ts">
import { onBeforeUnmount, ref, watch, nextTick } from "vue";
import {
  getDocument,
  GlobalWorkerOptions,
  TextLayer,
  type PDFDocumentProxy,
  type RenderTask,
  type PDFDocumentLoadingTask,
} from "pdfjs-dist";
import workerUrl from "pdfjs-dist/build/pdf.worker.min.mjs?url";
import { readerApi } from "./readerApi";
import { errorMessage } from "../../shared/api/client";
GlobalWorkerOptions.workerSrc = workerUrl;
const props = defineProps<{
  documentId: string;
  pageNumber: number;
  scale: number;
}>();
const canvas = ref<HTMLCanvasElement>(),
  layer = ref<HTMLDivElement>(),
  error = ref(""),
  loading = ref(false);
let task: PDFDocumentLoadingTask | undefined,
  pdf: PDFDocumentProxy | undefined,
  renderTask: RenderTask | undefined,
  textLayer: TextLayer | undefined,
  epoch = 0;
async function render() {
  const ticket = ++epoch;
  error.value = "";
  loading.value = true;
  renderTask?.cancel();
  textLayer?.cancel();
  try {
    if (!pdf) {
      const blob = await readerApi.blob(props.documentId);
      if (ticket !== epoch) return;
      task = getDocument({
        data: new Uint8Array(await blob.arrayBuffer()),
        useSystemFonts: true,
        useWorkerFetch: false,
      });
      pdf = await task.promise;
    }
    const page = await pdf.getPage(props.pageNumber);
    if (ticket !== epoch) return;
    await nextTick();
    const target = canvas.value,
      container = layer.value;
    if (!target || !container) return;
    const viewport = page.getViewport({ scale: props.scale });
    if (viewport.width > 20000 || viewport.height > 20000 || viewport.width * viewport.height > 20000000)
      throw new Error("原始页面尺寸超出安全显示范围，请切换阅读模式");
    target.width = viewport.width;
    target.height = viewport.height;
    container.replaceChildren();
    container.style.setProperty("--scale-factor", String(props.scale));
    container.style.width = `${viewport.width}px`;
    container.style.height = `${viewport.height}px`;
    renderTask = page.render({ canvas: target, viewport });
    await renderTask.promise;
    if (ticket !== epoch) return;
    textLayer = new TextLayer({
      textContentSource: await page.getTextContent(),
      container,
      viewport,
    });
    await textLayer.render();
  } catch (e) {
    if (ticket === epoch) error.value = errorMessage(e);
  } finally {
    if (ticket === epoch) loading.value = false;
  }
}
watch(() => [props.documentId, props.pageNumber, props.scale], render, {
  immediate: true,
});
onBeforeUnmount(() => {
  epoch++;
  renderTask?.cancel();
  textLayer?.cancel();
  void task?.destroy();
});
</script>
<template>
  <p v-if="error" role="alert" class="error-message">{{ error }}</p>
  <p v-if="loading" role="status">正在绘制原始页面…</p>
  <div class="pdf-scroll">
    <div class="pdf-page">
      <canvas ref="canvas" aria-label="PDF 原始页面" />
      <div ref="layer" class="textLayer" />
    </div>
  </div>
</template>
<style>
.pdf-scroll {
  max-width: 100%;
  overflow: auto;
}
.pdf-page {
  position: relative;
  width: max-content;
  max-width: none;
}
.pdf-page canvas {
  display: block;
}
.textLayer {
  position: absolute;
  inset: 0;
  overflow: clip;
  line-height: 1;
  text-align: initial;
  text-size-adjust: none;
  forced-color-adjust: none;
  transform-origin: 0 0;
  caret-color: CanvasText;
  z-index: 1;
}
.textLayer :is(span, br) {
  color: transparent;
  position: absolute;
  white-space: pre;
  cursor: text;
  transform-origin: 0 0;
}
.textLayer span.markedContent {
  top: 0;
  height: 0;
}
.textLayer ::selection {
  background: color-mix(in srgb, #176b45 35%, transparent);
}
</style>
