<script setup lang="ts">
import { ref } from "vue";
import { api, errorMessage } from "../../shared/api/client";
import { sourceTypeLabels, stateLabels, type SourceDocument } from "./api";
defineProps<{ item: SourceDocument }>();
const downloading = ref(false),
  error = ref("");
async function download(id: string, name: string) {
  downloading.value = true;
  error.value = "";
  try {
    const response = await api.http.get(`/files/${id}`, {
      responseType: "blob",
    });
    const url = URL.createObjectURL(response.data);
    const a = document.createElement("a");
    a.href = url;
    a.download = name;
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  } catch (e) {
    error.value = errorMessage(e);
  } finally {
    downloading.value = false;
  }
}
</script>
<template>
  <div>
    <div class="stage-meta">
      <span class="stage-tag">{{
        sourceTypeLabels[item.sourceType] || item.sourceType
      }}</span><span>{{
        stateLabels[item.reviewState] || item.reviewState
      }}</span
      ><span>{{ stateLabels[item.availability] || item.availability }}</span>
    </div>
    <dl class="stage-facts">
      <div>
        <dt>作者</dt>
        <dd>{{ item.author || "未填写" }}</dd>
      </div>
      <div>
        <dt>出版单位</dt>
        <dd>{{ item.publisher || "未填写" }}</dd>
      </div>
      <div>
        <dt>出版年份</dt>
        <dd>{{ item.publicationYear || "未填写" }}</dd>
      </div>
      <div v-if="item.sourceUrl">
        <dt>来源网址</dt>
        <dd>
          <a
            v-if="/^https?:\/\//i.test(item.sourceUrl)"
            :href="item.sourceUrl"
            target="_blank"
            rel="noopener noreferrer"
            >{{ item.sourceUrl }}</a><span v-else>{{ item.sourceUrl }}</span>
        </dd>
      </div>
      <div v-if="item.bibliographicNote">
        <dt>书目说明</dt>
        <dd>{{ item.bibliographicNote }}</dd>
      </div>
    </dl>
    <div v-if="item.fileId" class="stage-actions">
      <el-button
        :loading="downloading"
        @click="download(item.fileId!, item.fileName || item.title)"
        >下载原始资料</el-button
      ><span class="muted">{{ item.fileName }}</span>
    </div>
    <p v-if="error" role="alert" class="error-message">{{ error }}</p>
  </div>
</template>
