<script setup lang="ts">
import { computed, ref } from "vue";
import { useSessionStore } from "../../shared/stores/session";
import { api, errorMessage } from "../../shared/api/client";
import { sourceTypeLabels, stateLabels, type SourceDocument } from "./api";
const props=defineProps<{ item: SourceDocument }>();
const session = useSessionStore();
const privileged=computed(()=>props.item.submittedBy===session.user?.id||!!session.user?.permissions.includes('knowledge:review'));
const canRead=computed(()=>props.item.availability==='ACTIVE'&&(privileged.value||props.item.reviewState==='APPROVED'&&props.item.rights?.publicReadingAllowed));
const canDownload=computed(()=>props.item.availability==='ACTIVE'&&(privileged.value||props.item.reviewState==='APPROVED'&&props.item.rights?.publicDownloadAllowed));
const downloading = ref(false),
  error = ref("");
async function download(id: string, name: string) {
  downloading.value = true;
  error.value = "";
  try {
    const response = await api.http.get(`/source-documents/${id}/file`, {
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
      }}</span
      ><span>{{ stateLabels[item.reviewState] || item.reviewState }}</span
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
            >{{ item.sourceUrl }}</a
          ><span v-else>{{ item.sourceUrl }}</span>
        </dd>
      </div>
      <div v-if="item.bibliographicNote">
        <dt>书目说明</dt>
        <dd>{{ item.bibliographicNote }}</dd>
      </div>
    </dl>
    <section v-if="item.rights" class="stage-section">
      <h2>版权与使用范围</h2>
      <dl class="stage-facts">
        <div>
          <dt>许可类型</dt>
          <dd>{{ item.rights.licenseType || "未确认" }}</dd>
        </div>
        <div>
          <dt>版权或授权依据</dt>
          <dd>{{ item.rights.copyrightSource || "未填写" }}</dd>
        </div>
        <div>
          <dt>授权范围</dt>
          <dd>{{ item.rights.authorizationScope || "未填写" }}</dd>
        </div>
        <div
          v-for="entry in [
            ['publicReadingAllowed', '公开阅读'],
            ['publicDownloadAllowed', '公开下载'],
            ['quoteDisplayAllowed', '引文展示'],
            ['ragUseAllowed', 'RAG使用'],
          ] as const"
          :key="entry[0]"
        >
          <dt>{{ entry[1] }}</dt>
          <dd>{{ item.rights[entry[0]] ? "允许" : "未授权" }}</dd>
        </div>
      </dl>
      <p v-if="item.approvedUse">
        审核用途：{{
          item.approvedUse === "REFERENCE_ONLY"
            ? "仅供阅读参考"
            : "可靠文字引用"
        }}
      </p>
      <p v-if="item.reviewedRights" class="stage-file-note">
        提交审核时的授权：全文阅读{{ item.reviewedRights.publicReadingAllowed ? '允许' : '未授权' }}，原件下载{{ item.reviewedRights.publicDownloadAllowed ? '允许' : '未授权' }}，引文展示{{ item.reviewedRights.quoteDisplayAllowed ? '允许' : '未授权' }}，RAG使用{{ item.reviewedRights.ragUseAllowed ? '允许' : '未授权' }}。当前生效范围以上方记录为准。
      </p>
    </section>
    <p v-else class="stage-file-note">
      历史资料授权尚未确认，访问范围以服务器校验为准。
    </p>
    <RouterLink v-if="item.readingEditionId && canRead" :to="`/sources/${item.id}/read`"
      >打开来源阅读器</RouterLink
    >
    <div
      v-if="
        item.fileId && canDownload
      "
      class="stage-actions"
    >
      <el-button
        :loading="downloading"
        @click="download(item.id, item.fileName || item.title)"
        >下载原始资料</el-button
      ><span class="muted">{{ item.fileName }}</span>
    </div>
    <p v-if="error" role="alert" class="error-message">{{ error }}</p>
  </div>
</template>
