<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import {
  stageApi,
  sourceTypeLabels,
  type SourceInput,
  type SourceRights,
} from "./api";
import { readerApi, type Metadata } from "./readerApi";
import { errorMessage } from "../../shared/api/client";
import "./stage.css";
const route = useRoute(),
  router = useRouter();
let epoch = 0;
onBeforeUnmount(() => {
  epoch++;
});
const step = ref(1),
  busy = ref(false),
  error = ref(""),
  message = ref(""),
  fileName = ref(""),
  metadata = ref<Metadata | null>(null),
  id = ref<string | null>(null),
  version = ref<number>();
const rights = (): SourceRights => ({
  licenseType: "UNKNOWN",
  copyrightSource: "",
  authorizationScope: "",
  publicReadingAllowed: false,
  publicDownloadAllowed: false,
  quoteDisplayAllowed: false,
  ragUseAllowed: false,
});
const form = ref<SourceInput>({
  title: "",
  sourceType: "BOOK",
  rights: rights(),
});
const requestKey = ref(crypto.randomUUID());
const editing = computed(() => route.params.id && route.params.id !== "new");
async function upload(event: Event) {
  const ticket = epoch;
  const input = event.target as HTMLInputElement,
    file = input.files?.[0];
  if (!file) return;
  error.value = "";
  if (
    file.size > 50 * 1024 * 1024 ||
    !/\.(epub|pdf|doc|docx|txt)$/i.test(file.name)
  ) {
    error.value = "请选择不超过 50MB 的 EPUB、PDF、DOC、DOCX 或 UTF-8 TXT";
    input.value = "";
    return;
  }
  busy.value = true;
  try {
    const result = await stageApi.upload(file);
    if (ticket !== epoch) return;
    form.value.fileId = result.id;
    fileName.value = result.originalName;
    requestKey.value = crypto.randomUUID();
    step.value = 2;
    await readMetadata(ticket);
  } catch (e) {
    if (ticket === epoch) error.value = errorMessage(e);
  } finally {
    if (ticket === epoch) busy.value = false;
  }
}
async function readMetadata(ticket = epoch) {
  if (!form.value.fileId) return;
  busy.value = true;
  error.value = "";
  try {
    const suggestions = await readerApi.metadata(form.value.fileId);
    if (ticket !== epoch) return;
    metadata.value = suggestions;
    for (const key of [
      "title",
      "author",
      "publisher",
      "publicationYear",
    ] as const) {
      const suggestion = metadata.value[key];
      if (suggestion !== undefined && !form.value[key])
        (form.value as Record<string, unknown>)[key] = suggestion;
    }
  } catch (e) {
    if (ticket === epoch)
      error.value = `原件已保存，书目建议暂不可用：${errorMessage(e)}。可人工填写或重新读取。`;
  } finally {
    if (ticket === epoch) busy.value = false;
  }
}
async function save(confirm = false) {
  const ticket = epoch;
  busy.value = true;
  error.value = "";
  try {
    const payload = { ...form.value, expectedVersion: version.value };
    const result = confirm
      ? await readerApi.confirm({
          ...payload,
          documentId: id.value || undefined,
          requestKey: requestKey.value,
        })
      : await stageApi.saveSource(id.value, payload);
    if (ticket !== epoch) return;
    id.value = result.id;
    version.value = result.lockVersion;
    message.value = confirm
      ? "已确认，解析已入队"
      : "书目草稿已保存，尚未启动解析";
    if (confirm) await router.replace(`/sources/${result.id}`);
  } catch (e) {
    if (ticket === epoch) error.value = errorMessage(e);
  } finally {
    if (ticket === epoch) busy.value = false;
  }
}
watch(
  () => route.params.id,
  async () => {
    const ticket = ++epoch;
    step.value = 1;
    id.value = null;
    version.value = undefined;
    fileName.value = "";
    metadata.value = null;
    error.value = "";
    message.value = "";
    form.value = { title: "", sourceType: "BOOK", rights: rights() };
    requestKey.value = crypto.randomUUID();
    if (!editing.value) {
      busy.value = false;
      return;
    }
    busy.value = true;
    try {
      const d = await stageApi.source(String(route.params.id));
      if (ticket !== epoch) return;
      id.value = d.id;
      version.value = d.lockVersion;
      fileName.value = d.fileName || "";
      form.value = {
        title: d.title,
        sourceType: d.sourceType,
        fileId: d.fileId || undefined,
        author: d.author || "",
        publisher: d.publisher || "",
        publicationYear: d.publicationYear || undefined,
        sourceUrl: d.sourceUrl || "",
        bibliographicNote: d.bibliographicNote || "",
        rights: d.rights || rights(),
      };
      step.value = 2;
    } catch (e) {
      if (ticket === epoch) error.value = errorMessage(e);
    } finally {
      if (ticket === epoch) busy.value = false;
    }
  },
  { immediate: true },
);
</script>
<template>
  <section class="stage-page source-workflow source-wizard">
    <RouterLink to="/contribute?tab=sources">返回资料工作台</RouterLink>
    <h1>{{ editing ? "编辑来源书目" : "上传来源资料" }}</h1>
    <nav aria-label="上传步骤" class="stage-actions">
      <span :aria-current="step === 1 ? 'step' : undefined">1 上传文件</span
      ><span :aria-current="step === 2 ? 'step' : undefined">2 核对书目</span
      ><span :aria-current="step === 3 ? 'step' : undefined">3 授权与确认</span>
    </nav>
    <p v-if="error" role="alert" class="error-message">{{ error }}</p>
    <p v-if="message" role="status">{{ message }}</p>
    <section v-if="step === 1">
      <h2>从原始资料开始</h2>
      <p>
        支持 EPUB、PDF、DOC、DOCX 和 UTF-8 TXT，最大
        50MB。上传后可核对识别到的书目信息。
      </p>
      <label class="stage-field"
        >选择资料文件<input
          type="file"
          accept=".epub,.pdf,.doc,.docx,.txt"
          :disabled="busy"
          @change="upload"
      /></label>
      <p v-if="busy" role="status">正在上传并读取书目建议…</p>
    </section>
    <form v-else @submit.prevent="save(step === 3)">
      <p>{{ fileName }}</p>
      <section v-if="step === 2">
        <h2>核对书目</h2>
        <div class="stage-actions">
          <button type="button" :disabled="busy" @click="step = 1">
            重新选择文件</button
          ><button type="button" :disabled="busy" @click="readMetadata()">
            重新读取书目建议
          </button>
        </div>
        <p class="stage-hint">
          自动识别只是建议，请按原书核实并修改；识别到的版权文字不会自动转换为授权。
        </p>
        <ul v-if="metadata?.warnings.length">
          <li v-for="warning in metadata.warnings" :key="warning">
            {{ warning }}
          </li>
        </ul>
        <div class="stage-grid">
          <label class="stage-field"
            >书目标题<input
              v-model="form.title"
              required
              maxlength="255" /></label
          ><label class="stage-field"
            >来源类型<select v-model="form.sourceType">
              <option
                v-for="(label, key) in sourceTypeLabels"
                :key="key"
                :value="key"
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
            >来源网址<input v-model="form.sourceUrl" type="url"
          /></label>
        </div>
        <label class="stage-field"
          >书目说明<textarea v-model="form.bibliographicNote" rows="3" /></label
        ><button type="button" class="source-primary" :disabled="busy" @click="step = 3">
          下一步：授权与确认
        </button>
      </section>
      <section v-else-if="form.rights">
        <h2>授权与使用范围</h2>
        <blockquote v-if="metadata?.rightsText">
          原文件版权文字：{{ metadata.rightsText }}
        </blockquote>
        <label class="stage-field"
          >许可类型<select v-model="form.rights.licenseType" aria-label="许可类型" aria-describedby="license-guidance" required>
            <option value="" disabled>请选择许可类型</option>
            <option value="UNKNOWN">尚未确认</option>
            <option value="OWNED">本人持有版权</option>
            <option value="AUTHORIZED">已获授权</option>
            <option value="PUBLIC_DOMAIN">公有领域</option>
            <option value="OPEN_LICENSE">开放许可</option></select
          ><small id="license-guidance"
            >请核对当前电子版、校注与图版的授权，不依据原著年代自动判断。</small
          ></label
        ><label class="stage-field"
          >版权或授权依据<textarea
            v-model="form.rights.copyrightSource"
            required
            rows="3"
          /></label
        ><label class="stage-field"
          >授权范围<textarea
            v-model="form.rights.authorizationScope"
            required
            rows="3"
          />
        </label>
        <fieldset>
          <legend>分别确认允许的用途（默认均不开放）</legend>
          <label
            v-for="entry in [
              ['publicReadingAllowed', '公开阅读全文'],
              ['publicDownloadAllowed', '公开下载原文件'],
              ['quoteDisplayAllowed', '展示引文'],
              ['ragUseAllowed', '用于检索增强生成'],
            ] as const"
            :key="entry[0]"
            class="stage-field"
            ><span
              ><input v-model="form.rights[entry[0]]" type="checkbox" />{{
                entry[1]
              }}</span
            ></label
          >
        </fieldset>
        <p>
          确认后将异步解析；解析完成后仍需提交来源审核。保存书目不会启动解析。
        </p>
        <div class="stage-actions">
          <button type="button" @click="step = 2">返回书目</button
          ><button
            type="submit"
            class="source-primary"
            :disabled="busy || !form.fileId || !form.title.trim()"
          >
            确认并解析
          </button>
        </div>
      </section>
      <button
        type="button"
        :disabled="busy || !form.fileId || !form.title.trim()"
        @click="save(false)"
      >
        仅保存书目草稿
      </button>
    </form>
  </section>
</template>
