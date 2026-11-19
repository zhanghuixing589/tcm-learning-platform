<script setup lang="ts">
import { onMounted, ref } from "vue";
import { stageApi, patch, type Feedback } from "./api";
import { useWorkspace } from "./useWorkspace";
import WorkspaceState from "./WorkspaceState.vue";
const { loading, busy, error, actionError, message, load, act } =
  useWorkspace();
const items = ref<Feedback[]>([]),
  page = ref(1),
  total = ref(0),
  status = ref("OPEN"),
  resolutions = ref<Record<string, string>>({});
const labels: Record<string, string> = {
  OPEN: "待处理",
  PROCESSING: "处理中",
  RESOLVED: "已解决",
  REJECTED: "不采纳",
  ALL: "全部状态",
};
function refresh(next = page.value) {
  load(
    () => stageApi.feedback({ status: status.value, page: next, pageSize: 20 }),
    (value) => {
      items.value = value.items;
      total.value = value.total;
      page.value = next;
    },
  );
}
function resolve(
  item: Feedback,
  state: "PROCESSING" | "RESOLVED" | "REJECTED",
) {
  const resolution = resolutions.value[item.id]?.trim();
  if (!resolution) {
    actionError.value = "请填写纠错处理意见";
    return;
  }
  act(
    () =>
      patch<Feedback>(`/feedback/${item.id}`, { status: state, resolution }),
    (value) => {
      const index = items.value.findIndex((x) => x.id === item.id);
      if (index >= 0) items.value[index] = value;
    },
    "纠错处理结果已保存",
  );
}
onMounted(() => refresh(1));
</script>
<template>
  <section>
    <form class="stage-toolbar" @submit.prevent="refresh(1)">
      <label class="stage-field"
        >纠错处理状态<select v-model="status">
          <option v-for="(label, key) in labels" :key="key" :value="key">
            {{ label }}
          </option>
        </select></label
      ><el-button native-type="submit">筛选纠错</el-button>
    </form>
    <WorkspaceState
      :loading="loading"
      :error="error"
      :action-error="actionError"
      :message="message"
      @retry="refresh()"
      ><p v-if="!items.length" class="stage-hint">
        当前没有符合条件的纠错，可切换处理状态查看。
      </p>
      <ul class="stage-list">
        <li v-for="item in items" :key="item.id">
          <span class="stage-tag">{{
            labels[item.status] || item.status
          }}</span>
          <RouterLink :to="`/knowledge/${item.resourceId}`">{{
            item.title || "查看关联知识"
          }}</RouterLink>
          <p class="stage-prose">{{ item.content }}</p>
          <p v-if="item.resolution" class="stage-hint">
            处理意见：{{ item.resolution }}
          </p>
          <template v-if="['OPEN', 'PROCESSING'].includes(item.status)"
            ><label class="stage-field"
              >纠错处理意见<textarea
                v-model="resolutions[item.id]"
                rows="3"
                maxlength="5000"
              ></textarea>
            </label>
            <div class="stage-actions">
              <el-button :loading="busy" @click="resolve(item, 'PROCESSING')"
                >标记处理中</el-button
              ><el-button :loading="busy" @click="resolve(item, 'RESOLVED')"
                >标记已解决</el-button
              ><el-button :loading="busy" @click="resolve(item, 'REJECTED')"
                >不采纳并说明</el-button
              >
            </div></template
          >
        </li>
      </ul>
      <nav v-if="total" class="stage-pagination" aria-label="纠错分页">
        <el-button
          aria-label="纠错上一页"
          :disabled="page <= 1 || loading"
          @click="refresh(page - 1)"
          >上一页</el-button
        ><span>纠错第 {{ page }} 页，共 {{ total }} 项</span
        ><el-button
          aria-label="纠错下一页"
          :disabled="page * 20 >= total || loading"
          @click="refresh(page + 1)"
          >下一页</el-button
        >
      </nav></WorkspaceState
    >
  </section>
</template>
