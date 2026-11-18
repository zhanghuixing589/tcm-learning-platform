<script setup lang="ts">
import { computed } from "vue";
import type { LibrarySection, ProfileSummary } from "./personalCenter";
import { formatCount, formatDate } from "./personalCenter";
import UserAvatar from "./UserAvatar.vue";
import AccountIcon from "./AccountIcon.vue";
const props = defineProps<{
  summary: ProfileSummary | null;
  nickname?: string;
  bio?: string | null;
  loading?: boolean;
  own?: boolean;
  active?: string;
}>();
defineEmits<{ select: [section: LibrarySection] }>();
const stats = computed(() => [
  {
    key: "received-likes" as const,
    label: "获赞",
    value: props.summary?.receivedLikes,
  },
  {
    key: "following" as const,
    label: "关注",
    value: props.summary?.followingCount,
  },
  {
    key: "followers" as const,
    label: "粉丝",
    value: props.summary?.followerCount,
  },
]);
const roles = computed(() =>
  props.summary?.profile.roles?.includes("CONTRIBUTOR")
    ? ["内容贡献者"]
    : ["学习者"],
);
</script>
<template>
  <section class="profile-hero" aria-label="个人名片" :aria-busy="loading">
    <div class="profile-identity">
      <UserAvatar
        :nickname="summary?.profile.nickname || nickname || '学'"
        :url="summary?.profile.avatarUrl"
        large
      />
      <div class="profile-description">
        <div class="profile-name-row">
          <h1>{{ summary?.profile.nickname || nickname || "个人主页" }}</h1>
          <span v-for="role in roles" :key="role" class="profile-role">{{
            role
          }}</span>
        </div>
        <p class="profile-bio">
          {{
            summary?.profile.bio ||
            bio ||
            (own ? "写一句介绍，让大家认识你。" : "暂未填写简介")
          }}
        </p>
        <span v-if="summary?.profile.createdAt" class="profile-joined"
          ><AccountIcon name="calendar" :size="14" />
          {{ formatDate(summary.profile.createdAt) }} 加入</span
        >
      </div>
      <RouterLink v-if="own" class="profile-edit" to="/account/settings"
        ><AccountIcon name="pen" :size="16" />编辑资料</RouterLink
      >
      <slot name="action" />
    </div>
    <div class="profile-stats">
      <template v-for="stat in stats" :key="stat.key">
        <button
          v-if="own"
          class="profile-stat"
          :class="{ 'is-active': active === stat.key }"
          :disabled="!summary"
          :aria-label="`${stat.value === undefined ? '未加载' : stat.value} ${stat.label}`"
          @click="$emit('select', stat.key)"
        >
          <strong>{{
            stat.value === undefined ? "—" : formatCount(stat.value)
          }}</strong
          ><span>{{ stat.label }}</span>
        </button>
        <div
          v-else
          class="profile-stat"
          role="group"
          :aria-label="`${stat.value === undefined ? '未加载' : stat.value} ${stat.label}`"
        >
          <strong aria-hidden="true">{{
            stat.value === undefined ? "—" : formatCount(stat.value)
          }}</strong
          ><span aria-hidden="true">{{ stat.label }}</span>
        </div>
      </template>
    </div>
  </section>
</template>
