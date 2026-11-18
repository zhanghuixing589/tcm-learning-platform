<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { api, errorMessage } from "../../shared/api/client";
import type { Envelope, Page } from "../../shared/api/types";
import {
  formatDate,
  historyGroup,
  latestRequest,
  parseLibraryQuery,
  publicationLabel,
  sectionLabels,
  typeLabels,
  type ContentItem,
  type LibrarySection,
  type PublicUser,
} from "./personalCenter";
import AccountIcon from "./AccountIcon.vue";
import UserAvatar from "./UserAvatar.vue";
const props = defineProps<{ canPublish: boolean }>();
const emit = defineEmits<{ changed: [] }>();
const route = useRoute(),
  router = useRouter();
const filters = computed(() => parseLibraryQuery(route.query));
const relationship = computed(() =>
  ["following", "followers"].includes(filters.value.section),
);
const tabs: { key: LibrarySection; icon: string }[] = [
  { key: "bookmarks", icon: "bookmark" },
  { key: "history", icon: "clock" },
  { key: "publications", icon: "pen" },
  { key: "likes", icon: "heart" },
];
const search = ref(""),
  items = ref<ContentItem[]>([]),
  people = ref<PublicUser[]>([]),
  total = ref(0),
  loading = ref(true),
  error = ref(""),
  actionError = ref("");
const pending = ref<string[]>([]),
  notification = ref(""),
  undoItem = ref<{ id: string; section: "bookmarks" | "likes" } | null>(null);
const requests = latestRequest();
let undoTimer: ReturnType<typeof setTimeout> | undefined;
const statusOptions = [
  { value: "", label: "全部" },
  { value: "PUBLISHED", label: "已发布" },
  { value: "IN_REVIEW", label: "审核中" },
  { value: "DRAFT", label: "草稿" },
  { value: "NEEDS_CHANGES", label: "需修改" },
  { value: "SUSPENDED", label: "已下架" },
  { value: "ARCHIVED", label: "已归档" },
];
const empty = computed(
  () =>
    ({
      bookmarks: [
        "还没有收藏的内容",
        "把感兴趣的知识收藏起来，下次回来就能接着读。",
        "bookmark",
      ],
      history: [
        "还没有浏览记录",
        "读过的内容会留在这里，方便回来看。",
        "clock",
      ],
      publications: [
        "还没有发表内容",
        props.canPublish
          ? "从一篇有来源的分享开始，记录你的学习与发现。"
          : "成为内容贡献者，把你的学习与发现分享给更多人。",
        "pen",
      ],
      likes: [
        "还没有点赞的内容",
        "遇到有帮助的内容，点个赞，把认同留下来。",
        "heart",
      ],
      "received-likes": [
        "还没有收到赞",
        "你发表的内容收到的赞，会在这里汇总。",
        "heart",
      ],
      following: [
        "还没有关注的人",
        "在社区遇见感兴趣的分享者后，可以在主页关注对方。",
        "users",
      ],
      followers: [
        "还没有粉丝",
        "持续分享有帮助的内容，让更多人认识你。",
        "users",
      ],
    })[filters.value.section],
);
const filtered = computed(
  () => !!(filters.value.q || filters.value.type || filters.value.status),
);
async function navigate(changes: Record<string, string | number | undefined>) {
  const next = { ...filters.value, ...changes };
  await router.push({
    path: "/account",
    query: {
      section: next.section === "bookmarks" ? undefined : next.section,
      page: next.page > 1 ? String(next.page) : undefined,
      type: next.type || undefined,
      status: next.status || undefined,
      q: next.q || undefined,
    },
  });
}
async function select(section: LibrarySection) {
  await navigate({ section, page: 1, type: "", status: "", q: "" });
}
async function load() {
  const ticket = requests.begin();
  loading.value = true;
  error.value = "";
  const f = filters.value;
  search.value = f.q;
  try {
    const { data } = await api.http.get<
      Envelope<Page<ContentItem | PublicUser>>
    >(`/me/${f.section}`, {
      params: {
        page: f.page,
        pageSize: 20,
        q: f.q || undefined,
        type: f.type || undefined,
        status: f.status || undefined,
      },
    });
    if (!requests.isCurrent(ticket)) return;
    total.value = data.data.total;
    if (relationship.value) {
      people.value = data.data.items as PublicUser[];
      items.value = [];
    } else {
      items.value = data.data.items as ContentItem[];
      people.value = [];
    }
    if (f.page > 1 && !data.data.items.length && data.data.total > 0)
      await navigate({ page: f.page - 1 });
  } catch (e) {
    if (requests.isCurrent(ticket)) {
      error.value = errorMessage(e);
      items.value = [];
      people.value = [];
    }
  } finally {
    if (requests.isCurrent(ticket)) loading.value = false;
  }
}
watch(() => route.fullPath, load, { immediate: true });
let alive = true;
onBeforeUnmount(() => {
  alive = false;
  requests.invalidate();
  clearTimeout(undoTimer);
});
async function run(
  key: string,
  action: () => Promise<unknown>,
  message: string,
) {
  if (pending.value.includes(key)) return;
  pending.value.push(key);
  actionError.value = "";
  try {
    await action();
    if (!alive) return;
    notification.value = message;
    emit("changed");
    await load();
  } catch (e) {
    actionError.value = errorMessage(e);
  } finally {
    pending.value = pending.value.filter((value) => value !== key);
  }
}
async function remove(item: ContentItem) {
  const section = filters.value.section;
  const endpoint =
    section === "history"
      ? `/me/history/${item.id}`
      : `/resources/${item.id}/${section === "bookmarks" ? "bookmark" : "like"}`;
  await run(
    item.id,
    async () => {
      await api.http.delete(endpoint);
      if (!alive) return;
      if (section === "bookmarks" || section === "likes") {
        undoItem.value = { id: item.id, section };
        clearTimeout(undoTimer);
        undoTimer = setTimeout(() => {
          undoItem.value = null;
        }, 8000);
      }
    },
    section === "history"
      ? "已删除这条浏览记录"
      : section === "bookmarks"
        ? "已取消收藏"
        : "已取消点赞",
  );
}
async function undo() {
  const previous = undoItem.value;
  if (!previous) return;
  await run(
    `undo-${previous.id}`,
    async () => {
      await api.http.put(
        `/resources/${previous.id}/${previous.section === "bookmarks" ? "bookmark" : "like"}`,
      );
      undoItem.value = null;
    },
    previous.section === "bookmarks" ? "已恢复收藏" : "已恢复点赞",
  );
}
async function follow(user: PublicUser) {
  await run(
    user.id,
    () =>
      user.following
        ? api.http.delete(`/users/${user.id}/follow`)
        : api.http.put(`/users/${user.id}/follow`),
    user.following ? "已取消关注" : "已关注",
  );
}
async function moveTab(event: KeyboardEvent, index: number) {
  if (!["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) return;
  event.preventDefault();
  const next =
    event.key === "Home"
      ? 0
      : event.key === "End"
        ? tabs.length - 1
        : (index + (event.key === "ArrowRight" ? 1 : -1) + tabs.length) %
          tabs.length;
  await select(tabs[next].key);
  await nextTick();
  document.getElementById(`library-tab-${tabs[next].key}`)?.focus();
}
function groupAt(index: number) {
  return (
    filters.value.section === "history" &&
    (index === 0 ||
      historyGroup(items.value[index].occurredAt) !==
        historyGroup(items.value[index - 1].occurredAt))
  );
}
</script>
<template>
  <section class="account-library" aria-label="个人内容">
    <div class="library-tabs" role="tablist" aria-label="个人内容分类">
      <button
        v-for="(tab, index) in tabs"
        :id="`library-tab-${tab.key}`"
        :key="tab.key"
        role="tab"
        :aria-selected="filters.section === tab.key"
        aria-controls="library-panel"
        :tabindex="
          filters.section === tab.key ||
          (!tabs.some((t) => t.key === filters.section) && index === 0)
            ? 0
            : -1
        "
        @click="select(tab.key)"
        @keydown="moveTab($event, index)"
      >
        <AccountIcon :name="tab.icon" :size="18" />{{ sectionLabels[tab.key] }}
      </button>
    </div>
    <div
      id="library-panel"
      :role="
        tabs.some((t) => t.key === filters.section) ? 'tabpanel' : 'region'
      "
      :aria-labelledby="
        tabs.some((t) => t.key === filters.section)
          ? `library-tab-${filters.section}`
          : undefined
      "
      :aria-label="sectionLabels[filters.section]"
      :aria-busy="loading"
      tabindex="0"
    >
      <div
        v-if="relationship || filters.section === 'received-likes'"
        class="library-section-heading"
      >
        <h2>{{ sectionLabels[filters.section] }}</h2>
        <button class="text-button" @click="select('bookmarks')">
          返回收藏
        </button>
      </div>
      <div class="library-toolbar">
        <div v-if="!relationship" class="library-filters">
          <label v-if="filters.section === 'publications'" class="filter-select"
            ><span class="sr-only">发表状态</span
            ><select
              :value="filters.status"
              @change="
                navigate({
                  status: ($event.target as HTMLSelectElement).value,
                  page: 1,
                })
              "
            >
              <option
                v-for="status in statusOptions"
                :key="status.value"
                :value="status.value"
              >
                {{ status.label }}
              </option>
            </select></label
          ><label class="filter-select"
            ><span class="sr-only">内容类型</span
            ><select
              :value="filters.type"
              @change="
                navigate({
                  type: ($event.target as HTMLSelectElement).value,
                  page: 1,
                })
              "
            >
              <option value="">全部类型</option>
              <option
                v-for="(name, type) in typeLabels"
                :key="type"
                :value="type"
              >
                {{ name }}
              </option>
            </select></label
          >
        </div>
        <form
          class="library-search"
          role="search"
          @submit.prevent="navigate({ q: search.trim(), page: 1 })"
        >
          <label class="sr-only" for="library-search">搜索当前列表</label
          ><input
            id="library-search"
            v-model="search"
            type="search"
            maxlength="100"
            :placeholder="relationship ? '搜索用户昵称' : '搜索当前列表'"
          /><button type="submit" aria-label="搜索">
            <AccountIcon name="search" :size="18" />
          </button>
        </form>
      </div>
      <div class="library-list-meta">
        <span>{{
          loading
            ? "正在整理内容…"
            : error
              ? "内容暂未加载"
              : `共 ${total} ${relationship ? "人" : "项"}`
        }}</span><el-popconfirm
          v-if="
            filters.section === 'history' && total > 0 && !loading && !error
          "
          title="清空全部浏览记录？收藏和发表不会受影响。"
          confirm-button-text="清空记录"
          cancel-button-text="保留"
          @confirm="
            run(
              'clear-history',
              () => api.http.delete('/me/history'),
              '浏览记录已清空',
            )
          "
          ><template #reference
            ><button
              class="text-button"
              :disabled="pending.includes('clear-history')"
            >
              清空记录
            </button></template
          ></el-popconfirm
        ><RouterLink
          v-if="filters.section === 'publications' && canPublish"
          class="text-button"
          to="/account/articles/new"
          ><AccountIcon name="plus" :size="16" />发表内容</RouterLink
        >
      </div>
      <p v-if="actionError" role="alert" class="error-message">
        {{ actionError }}
      </p>
      <div v-if="notification" role="status" class="library-feedback">
        <AccountIcon name="check" :size="16" /><span>{{ notification }}</span
        ><button
          v-if="undoItem"
          class="text-button"
          :disabled="pending.includes(`undo-${undoItem.id}`)"
          @click="undo"
        >
          撤销</button
        ><button
          class="feedback-close"
          aria-label="关闭提示"
          @click="
            notification = '';
            undoItem = null;
          "
        >
          ×
        </button>
      </div>
      <div v-if="loading" class="library-skeleton">
        <el-skeleton :rows="7" animated />
      </div>
      <div v-else-if="error" class="library-empty">
        <AccountIcon name="book" :size="36" />
        <h2>内容暂时没有加载出来</h2>
        <p>{{ error }}</p>
        <el-button @click="load">重新加载</el-button>
      </div>
      <div v-else-if="!items.length && !people.length" class="library-empty">
        <span class="empty-symbol"
          ><AccountIcon :name="filtered ? 'search' : empty[2]" :size="32"
        /></span>
        <h2>{{ filtered ? "没有找到匹配的结果" : empty[0] }}</h2>
        <p>{{ filtered ? "试试其他关键词，或清除筛选条件。" : empty[1] }}</p>
        <el-button
          v-if="filtered"
          @click="navigate({ q: '', type: '', status: '', page: 1 })"
          >清除筛选</el-button
        ><RouterLink
          v-else-if="filters.section === 'publications'"
          class="empty-action"
          :to="canPublish ? '/account/articles/new' : '/applications'"
          >{{ canPublish ? "写第一篇内容" : "申请贡献资格"
          }}<AccountIcon name="arrow" :size="16"
        /></RouterLink>
      </div>
      <ul v-else-if="relationship" class="people-list">
        <li v-for="user in people" :key="user.id">
          <RouterLink :to="`/users/${user.id}`" class="person-identity"
            ><UserAvatar :nickname="user.nickname" :url="user.avatarUrl" /><span
              ><strong>{{ user.nickname }}</strong
              ><span class="person-bio">{{
                user.bio || "暂未填写简介"
              }}</span></span
            ></RouterLink
          ><button
            class="follow-button"
            :class="{ 'is-following': user.following }"
            :disabled="pending.includes(user.id)"
            :aria-label="`${user.following ? '取消关注' : '关注'} ${user.nickname}`"
            @click="follow(user)"
          >
            <AccountIcon
              :name="user.following ? 'check' : 'plus'"
              :size="16"
            />{{
              user.following
                ? user.followsYou
                  ? "互相关注"
                  : "已关注"
                : "关注"
            }}
          </button>
        </li>
      </ul>
      <ul v-else class="content-list">
        <li v-for="(item, index) in items" :key="item.id">
          <h3 v-if="groupAt(index)" class="history-date">
            {{ historyGroup(item.occurredAt) }}
          </h3>
          <article
            class="content-entry"
            :class="{
              'content-unavailable':
                !item.available && filters.section !== 'publications',
            }"
          >
            <div class="entry-type">
              <span>{{ typeLabels[item.resourceType] || "内容" }}</span><span
                v-if="filters.section === 'publications'"
                class="publication-state"
                :class="{ 'state-review': item.revisionState === 'IN_REVIEW' }"
                >{{
                  publicationLabel(item.visibilityStatus, item.revisionState)
                }}</span
              >
            </div>
            <h3>
              <RouterLink
                v-if="filters.section === 'publications'"
                :to="`/account/${item.resourceType === 'POST' ? 'posts' : 'articles'}/${item.id}`"
                >{{ item.title || "未命名内容" }}</RouterLink><RouterLink
                v-else-if="item.available"
                :to="`/resources/${item.id}`"
                >{{ item.title }}</RouterLink
              ><span v-else>内容暂不可查看</span>
            </h3>
            <p
              v-if="item.available || filters.section === 'publications'"
              class="entry-summary"
            >
              {{ item.summary }}
            </p>
            <div class="entry-footer">
              <div class="entry-meta">
                <RouterLink
                  v-if="item.available && item.ownerId"
                  :to="`/users/${item.ownerId}`"
                  >{{ item.ownerNickname }}</RouterLink><span
                  v-if="item.sourceName"
                  >来源：{{ item.sourceName }}</span
                ><time :datetime="item.occurredAt">{{
                  formatDate(item.occurredAt, filters.section === "history")
                }}</time
                ><span
                  v-if="
                    filters.section === 'received-likes' ||
                    filters.section === 'publications'
                  "
                  >{{ item.likeCount }} 获赞</span
                ><span v-if="filters.section === 'publications'"
                  >{{ item.bookmarkCount }} 收藏</span
                >
              </div>
              <button
                v-if="
                  ['bookmarks', 'history', 'likes'].includes(filters.section)
                "
                class="entry-action"
                :disabled="pending.includes(item.id)"
                @click="remove(item)"
              >
                {{
                  filters.section === "bookmarks"
                    ? "取消收藏"
                    : filters.section === "history"
                      ? "删除记录"
                      : "取消点赞"
                }}</button><RouterLink
                v-else-if="filters.section === 'publications'"
                class="entry-action"
                :to="`/account/${item.resourceType === 'POST' ? 'posts' : 'articles'}/${item.id}`"
                >{{
                  item.resourceType === "ARTICLE" &&
                  item.revisionState === "DRAFT"
                    ? "继续编辑"
                    : "查看详情"
                }}</RouterLink
              >
            </div>
          </article>
        </li>
      </ul>
      <el-pagination
        v-if="!loading && !error && total > 20"
        class="library-pagination"
        :current-page="filters.page"
        :total="total"
        :page-size="20"
        layout="prev, pager, next"
        :pager-count="5"
        @current-change="navigate({ page: $event })"
      />
    </div>
  </section>
</template>
