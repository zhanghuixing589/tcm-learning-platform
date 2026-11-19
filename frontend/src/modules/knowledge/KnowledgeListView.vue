<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import {
  stageApi,
  typeLabels,
  type Category,
  type KnowledgeSummary,
} from "./api";
import { useWorkspace } from "./useWorkspace";
import WorkspaceState from "./WorkspaceState.vue";
import PaginationBar from "./PaginationBar.vue";
import "./stage.css";
const route = useRoute(),
  router = useRouter();
const { loading, error, load } = useWorkspace();
const items = ref<KnowledgeSummary[]>([]),
  categories = ref<Category[]>([]),
  total = ref(0),
  q = ref(""),
  type = ref(""),
  categoryId = ref("");
const page = computed(() => Math.max(1, Number(route.query.page) || 1));
function search(next = 1) {
  router.push({
    path: "/knowledge",
    query: {
      q: q.value || undefined,
      type: type.value || undefined,
      categoryId: categoryId.value || undefined,
      page: next > 1 ? next : undefined,
    },
  });
}
function refresh() {
  q.value = String(route.query.q || "");
  type.value = String(route.query.type || "");
  categoryId.value = String(route.query.categoryId || "");
  load(
    async () => ({
      list: await stageApi.knowledge({
        q: q.value,
        type: type.value,
        categoryId: categoryId.value,
        page: page.value,
      }),
      categories: await stageApi.categories(),
    }),
    (result) => {
      items.value = result.list.items;
      total.value = result.list.total;
      categories.value = result.categories;
    },
  );
}
watch(() => route.fullPath, refresh, { immediate: true });
</script>
<template>
  <section class="stage-page">
    <header class="stage-heading">
      <div>
        <h1>知识中心</h1>
        <p>阅读经过审核的中医知识，沿来源依据核对原文。</p>
      </div>
      <RouterLink class="primary-link" to="/formulas/compare"
        >比较方剂</RouterLink
      >
    </header>
    <form class="stage-toolbar" @submit.prevent="search()">
      <label class="stage-field search-field"
        >搜索知识<input
          v-model="q"
          placeholder="规范名称、别名或标题"
          maxlength="100" /></label
      ><label class="stage-field"
        >知识类型<select v-model="type">
          <option value="">全部类型</option>
          <option v-for="(label, key) in typeLabels" :key="key" :value="key">
            {{ label }}
          </option>
        </select></label
      ><label class="stage-field"
        >分类<select v-model="categoryId">
          <option value="">全部分类</option>
          <option
            v-for="category in categories"
            :key="category.id"
            :value="category.id"
          >
            {{ category.name }}
          </option>
        </select></label
      ><el-button type="primary" native-type="submit">搜索</el-button>
    </form>
    <WorkspaceState :loading="loading" :error="error" @retry="refresh"
      ><ul v-if="items.length" class="stage-list">
        <li v-for="item in items" :key="item.id">
          <div class="stage-meta">
            <span class="stage-tag">{{ typeLabels[item.type] }}</span><span
              v-if="item.categoryName"
              >{{ item.categoryName }}</span
            ><span>第 {{ item.versionNo }} 版</span>
          </div>
          <h2>
            <RouterLink :to="`/knowledge/${item.id}`">{{
              item.title
            }}</RouterLink>
          </h2>
          <p>{{ item.summary }}</p>
          <span v-if="item.canonicalName !== item.title" class="stage-hint"
            >规范名称：{{ item.canonicalName }}</span
          >
        </li>
      </ul>
      <div v-else class="stage-empty">
        <h2>没有找到符合条件的知识</h2>
        <p>
          试着缩短关键词、切换类型或选择全部分类。只有已审核且有效的正式版本会展示在这里。
        </p>
      </div>
      <PaginationBar :page="page" :total="total" @change="search"
    /></WorkspaceState>
  </section>
</template>
