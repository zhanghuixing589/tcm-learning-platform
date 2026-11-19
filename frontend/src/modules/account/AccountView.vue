<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { api, errorMessage } from "../../shared/api/client";
import type { Envelope, Page, RoleApplication } from "../../shared/api/types";
import { useSessionStore } from "../../shared/stores/session";
import {
  latestRequest,
  parseLibraryQuery,
  type LibrarySection,
  type ProfileSummary,
} from "./personalCenter";
import ProfileHero from "./ProfileHero.vue";
import AccountContent from "./AccountContent.vue";
import AccountIcon from "./AccountIcon.vue";
import "./account.css";
const session = useSessionStore(),
  route = useRoute(),
  router = useRouter();
const summary = ref<ProfileSummary | null>(null),
  summaryError = ref(""),
  loading = ref(true),
  logoutBusy = ref(false),
  logoutError = ref("");
const application = ref<RoleApplication | null>(null),
  applicationError = ref("");
const summaryRequests = latestRequest();
const canPublish = computed(
  () => session.user?.permissions.includes("community:article:submit") || false,
);
const active = computed(() => parseLibraryQuery(route.query).section);
const contributionText = computed(() =>
  canPublish.value
    ? "你已获得内容贡献资格，可以提交有据可查的学习分享。"
    : application.value?.status === "PENDING"
      ? "你的申请正在审核中，处理结果会保留在申请记录里。"
      : application.value?.status === "REJECTED"
        ? "申请暂未通过，查看审核意见后可重新提交。"
        : "分享有来源的知识，让你的学习积累帮助更多人。",
);
async function loadSummary() {
  const ticket = summaryRequests.begin();
  summaryError.value = "";
  loading.value = true;
  try {
    const result = (
      await api.http.get<Envelope<ProfileSummary>>("/me/profile-summary")
    ).data.data;
    if (summaryRequests.isCurrent(ticket)) summary.value = result;
  } catch (e) {
    if (summaryRequests.isCurrent(ticket)) summaryError.value = errorMessage(e);
  } finally {
    if (summaryRequests.isCurrent(ticket)) loading.value = false;
  }
}
async function loadApplication() {
  applicationError.value = "";
  try {
    application.value =
      (
        await api.http.get<Envelope<Page<RoleApplication>>>(
          "/me/role-applications",
          { params: { pageSize: 1 } },
        )
      ).data.data.items[0] || null;
  } catch (e) {
    applicationError.value = errorMessage(e);
  }
}
async function select(section: LibrarySection) {
  await router.push({ path: "/account", query: { section } });
}
async function logout() {
  logoutBusy.value = true;
  logoutError.value = "";
  try {
    await session.logout();
    await router.push("/login");
  } catch (e) {
    logoutError.value = errorMessage(e);
  } finally {
    logoutBusy.value = false;
  }
}
onMounted(() => {
  void loadSummary();
  void loadApplication();
});
onBeforeUnmount(() => summaryRequests.invalidate());
</script>
<template>
  <div class="personal-center">
    <div class="account-page-heading">
      <p>个人中心</p>
      <span>在这里，找回每一次学习与分享。</span>
    </div>
    <ProfileHero
      :summary="summary"
      :nickname="session.user?.nickname"
      :bio="session.user?.bio"
      :loading="loading"
      :active="active"
      own
      @select="select"
    />
    <div v-if="summaryError" class="summary-error" role="alert">
      <span>互动数据暂未加载：{{ summaryError }}</span
      ><button class="text-button" :disabled="loading" @click="loadSummary">
        重试
      </button>
    </div>
    <div class="personal-center-layout">
      <AccountContent :can-publish="canPublish" @changed="loadSummary" />
      <aside class="profile-rail" aria-label="学习与账户入口">
        <section class="rail-section">
          <div class="rail-heading">
            <AccountIcon name="book" :size="19" />
            <h2>学习，慢慢积累</h2>
          </div>
          <p>从认识一味药，到理解一张方。每一次阅读，都是新的起点。</p>
          <div class="learning-status">
            <span class="learning-status-dot"></span
            ><span>专题学习正在准备中</span>
          </div>
          <p class="rail-footnote">开放后，这里会显示你的学习记录。</p>
        </section>
        <section class="rail-section">
          <div class="rail-heading">
            <AccountIcon name="pen" :size="19" />
            <h2>让知识被看见</h2>
          </div>
          <span v-if="canPublish" class="contribution-badge">内容贡献者</span>
          <p>{{ contributionText }}</p>
          <p v-if="applicationError" class="rail-error" role="alert">
            申请状态加载失败。<button
              class="text-button"
              @click="loadApplication"
            >
              重试
            </button>
          </p>
          <RouterLink class="rail-link" to="/applications"
            >{{
              canPublish
                ? "查看贡献资格"
                : application
                  ? "查看申请记录"
                  : "申请成为贡献者"
            }}<AccountIcon name="arrow" :size="16"
          /></RouterLink>
        </section>
        <section class="rail-section rail-settings">
          <h2>账户设置</h2>
          <RouterLink to="/account/settings"
            ><AccountIcon name="settings" :size="17" />编辑资料<AccountIcon
              name="arrow"
              :size="15" /></RouterLink
          ><button :disabled="logoutBusy" @click="logout">
            <AccountIcon name="logout" :size="17" />{{
              logoutBusy ? "正在退出…" : "退出登录"
            }}
          </button>
          <p v-if="logoutError" role="alert" class="rail-error">
            {{ logoutError }}
          </p>
        </section>
      </aside>
    </div>
  </div>
</template>
