<script setup lang="ts">
import { reactive, ref, watch } from "vue";
import { characterCount } from "./editor";
import { rateOperations, syntaxOptions, type TextPolicyConfig } from "./types";

const props = defineProps<{ baseline: TextPolicyConfig; busy: boolean }>();
const emit = defineEmits<{ save: [config: TextPolicyConfig, reason: string] }>();
const clone = (value: TextPolicyConfig): TextPolicyConfig => JSON.parse(JSON.stringify(value));
const config = reactive(clone(props.baseline));
const reason = ref("");
const error = ref("");
watch(() => props.baseline, (value) => { Object.assign(config, clone(value)); });
const fields = [
  ["maxTitleChars", "标题字符", 255], ["maxPostChars", "讨论正文字符", 500000],
  ["maxArticleChars", "文章默认正文字符", 500000], ["maxArticleHardChars", "文章正文硬上限", 500000],
  ["maxCommentChars", "评论字符", 65535], ["maxReferences", "单篇文献数量", 300],
  ["maxReferenceChars", "文献字段总字符", 200000], ["maxRequestBytes", "请求体字节", 8388608],
] as const;
const labels: Record<typeof syntaxOptions[number], string> = {
  paragraph: "段落", heading: "标题", emphasis: "斜体", strong: "加粗", blockquote: "引文",
  code: "行内代码", codeBlock: "代码块", list: "列表", link: "链接", table: "表格", hr: "分隔线",
};
const rateLabels: Record<typeof rateOperations[number], string> = {
  POST_CREATE: "发布讨论", COMMENT_CREATE: "发布评论", PUBLIC_EDIT: "修订公开内容",
  DRAFT_SAVE: "保存私有草稿", QUOTA_REQUEST: "申请扩容",
};
function submit() {
  if (props.busy) return;
  if (config.maxArticleChars > config.maxArticleHardChars) {
    error.value = "文章默认额度不能超过系统硬上限。";
    return;
  }
  if (rateOperations.some((operation) => new Set(config.rates[operation].map((window) => window.seconds)).size !== config.rates[operation].length)) {
    error.value = "同一操作的限频窗口秒数不能重复。";
    return;
  }
  if (!reason.value.trim() || characterCount(reason.value) > 1000) {
    error.value = "请填写 1 至 1000 字符的变更原因。";
    return;
  }
  error.value = "";
  emit("save", clone(config), reason.value);
}
</script>

<template>
  <form class="community-panel community-policy" @submit.prevent="submit">
    <h2>新建文本政策版本</h2>
    <p class="muted">新建后保持待启用，可在版本记录中单独启用。</p>
    <p v-if="error" role="alert" class="error-message">{{ error }}</p>
    <fieldset :disabled="busy">
      <legend>可用语法</legend>
      <div class="community-actions">
        <label v-for="syntax in syntaxOptions" :key="syntax" class="community-check">
          <input v-model="config.allowedSyntax" type="checkbox" :value="syntax" :disabled="syntax === 'paragraph'" /> {{ labels[syntax] }}
        </label>
      </div>
    </fieldset>
    <fieldset :disabled="busy">
      <legend>网页链接协议</legend>
      <div class="community-actions">
        <label class="community-check"><input v-model="config.allowedProtocols" type="checkbox" value="https" disabled /> HTTPS</label>
        <label class="community-check"><input v-model="config.allowedProtocols" type="checkbox" value="http" /> HTTP</label>
      </div>
    </fieldset>
    <fieldset :disabled="busy">
      <legend>字符、文献和请求体额度</legend>
      <div class="community-form-grid">
        <label v-for="[key, label, max] in fields" :key="key" class="community-field">{{ label }}
          <input v-model.number="config[key]" type="number" min="1" :max="max" step="1" required />
        </label>
      </div>
    </fieldset>
    <fieldset v-for="operation in rateOperations" :key="operation" :disabled="busy">
      <legend>{{ rateLabels[operation] }}限频</legend>
      <div v-for="(window, index) in config.rates[operation]" :key="index" class="community-actions">
        <label class="community-field">窗口秒数<input v-model.number="window.seconds" type="number" min="1" max="86400" required /></label>
        <label class="community-field">次数上限<input v-model.number="window.limit" type="number" min="1" max="1000000" required /></label>
        <button type="button" :disabled="config.rates[operation].length <= 1" @click="config.rates[operation].splice(index, 1)">移除窗口</button>
      </div>
      <button type="button" :disabled="config.rates[operation].length >= 8"
        @click="config.rates[operation].push({ seconds: 60, limit: 1 })">添加限频窗口</button>
    </fieldset>
    <label class="community-field">变更原因<textarea v-model="reason" rows="3" :readonly="busy" required></textarea></label>
    <button class="community-primary" type="submit" :disabled="busy">保存为新版本</button>
  </form>
</template>
