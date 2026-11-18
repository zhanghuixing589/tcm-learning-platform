<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { api, errorMessage } from "../../shared/api/client";
import type { Envelope, Page } from "../../shared/api/types";
import { useSessionStore } from "../../shared/stores/session";
import {
  formatDate,
  latestRequest,
  type ContentItem,
  type ProfileSummary,
} from "./personalCenter";
import ProfileHero from "./ProfileHero.vue";
import AccountIcon from "./AccountIcon.vue";
import "./account.css";
const route = useRoute(),
  session = useSessionStore();
const summary = ref<ProfileSummary | null>(null),
  items = ref<ContentItem[]>([]),
  page = ref(1),
  total = ref(0);
const profileLoading = ref(true),
  listLoading = ref(true),
  error = ref(""),
  listError = ref(""),
  followingBusy = ref(false),
  followError = ref("");
const profileRequests = latestRequest(),
  listRequests = latestRequest();
let mutationVersion = 0;
async function loadProfile() {
  const ticket = profileRequests.begin(),
    userId = String(route.params.userId);
  profileLoading.value = true;
  error.value = "";
  try {
    const response = await api.http.get<Envelope<ProfileSummary>>(
      `/users/${userId}/profile`,
    );
    if (profileRequests.isCurrent(ticket)) summary.value = response.data.data;
  } catch (e) {
    if (profileRequests.isCurrent(ticket)) error.value = errorMessage(e);
  } finally {
    if (profileRequests.isCurrent(ticket)) profileLoading.value = false;
  }
}
async function loadItems() {
  const ticket = listRequests.begin(),
    userId = String(route.params.userId);
  listLoading.value = true;
  listError.value = "";
  try {
    const response = await api.http.get<Envelope<Page<ContentItem>>>(
      `/users/${userId}/publications`,
      { params: { page: page.value, pageSize: 20 } },
    );
    if (listRequests.isCurrent(ticket)) {
      items.value = response.data.data.items;
      total.value = response.data.data.total;
    }
  } catch (e) {
    if (listRequests.isCurrent(ticket)) listError.value = errorMessage(e);
  } finally {
    if (listRequests.isCurrent(ticket)) listLoading.value = false;
  }
}
async function toggleFollow() {
  if (!summary.value || followingBusy.value) return;
  const userId = summary.value.profile.id,
    ticket = ++mutationVersion,
    wasFollowing = summary.value.following;
  followingBusy.value = true;
  followError.value = "";
  const current = () =>
    ticket === mutationVersion && route.params.userId === userId;
  try {
    if (wasFollowing) await api.http.delete(`/users/${userId}/follow`);
    else await api.http.put(`/users/${userId}/follow`);
    if (current()) await loadProfile();
  } catch (e) {
    if (current()) followError.value = errorMessage(e);
  } finally {
    if (current()) followingBusy.value = false;
  }
}
watch(
  () => route.params.userId,
  () => {
    mutationVersion++;
    followingBusy.value = false;
    followError.value = "";
    page.value = 1;
    summary.value = null;
    items.value = [];
    total.value = 0;
    void loadProfile();
    void loadItems();
  },
  { immediate: true },
);
onBeforeUnmount(() => {
  mutationVersion++;
  profileRequests.invalidate();
  listRequests.invalidate();
});
</script>
<template>
  <div class="personal-center">
    <RouterLink class="account-back" to="/account"
      ><AccountIcon name="back" :size="18" />返回个人中心</RouterLink
    >
    <div v-if="error && !summary" class="library-empty">
      <h1>暂时无法查看这个主页</h1>
      <p>{{ error }}</p>
      <el-button @click="loadProfile">重新加载主页</el-button>
    </div>
    <template v-else
      ><ProfileHero :summary="summary" :loading="profileLoading"
        ><template #action
          ><RouterLink
            v-if="summary?.profile.id === session.user?.id"
            class="profile-edit"
            to="/account"
            >我的个人中心</RouterLink
          ><button
            v-else-if="summary"
            class="follow-button"
            :class="{ 'is-following': summary.following }"
            :disabled="followingBusy"
            :aria-label="summary.following ? '取消关注' : '关注'"
            @click="toggleFollow"
          >
            <AccountIcon
              :name="summary.following ? 'check' : 'plus'"
              :size="16"
            />{{
              summary.following
                ? summary.followsYou
                  ? "互相关注"
                  : "已关注"
                : "关注"
            }}
          </button></template
        ></ProfileHero
      >
      <p v-if="followError || error" role="alert" class="error-message">
        {{ followError || error }}
      </p>
      <section class="public-content">
        <h2>
          公开发表
          <span v-if="!listLoading && !listError" class="muted">{{
            total
          }}</span>
        </h2>
        <el-skeleton v-if="listLoading" :rows="5" animated />
        <div v-else-if="listError" class="library-empty">
          <h2>发表内容暂未加载</h2>
          <p>{{ listError }}</p>
          <el-button @click="loadItems">重新加载列表</el-button>
        </div>
        <div v-else-if="!items.length" class="library-empty">
          <span class="empty-symbol"
            ><AccountIcon name="pen" :size="32"
          /></span>
          <h2>还没有公开发表的内容</h2>
          <p>有新的分享后，会展示在这里。</p>
        </div>
        <ul v-else class="content-list">
          <li v-for="item in items" :key="item.id">
            <article class="content-entry">
              <h3>
                <RouterLink :to="`/resources/${item.id}`">{{
                  item.title
                }}</RouterLink>
              </h3>
              <p class="entry-summary">{{ item.summary }}</p>
              <div class="entry-meta">
                <time :datetime="item.occurredAt">{{
                  formatDate(item.occurredAt)
                }}</time
                ><span>{{ item.likeCount }} 获赞</span>
              </div>
            </article>
          </li>
        </ul>
        <el-pagination
          v-if="!listError && total > 20"
          :current-page="page"
          :total="total"
          :page-size="20"
          layout="prev, pager, next"
          @current-change="
            page = $event;
            loadItems();
          "
        />
      </section>
    </template>
  </div>
</template>
