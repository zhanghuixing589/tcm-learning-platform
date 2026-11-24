<script setup lang="ts">
import UserAvatar from "../account/UserAvatar.vue";
import { formatDate } from "../account/personalCenter";
import type { Comment } from "./types";

defineProps<{ item: Comment; busy: boolean; writable: boolean }>();
defineEmits<{ reply: [item: Comment]; edit: [item: Comment]; remove: [item: Comment] }>();
</script>

<template>
  <article class="community-comment">
    <template v-if="item.displayState === 'VISIBLE' && item.author">
      <div class="community-meta">
        <UserAvatar :nickname="item.author.nickname" :url="item.author.avatarUrl" />
        <RouterLink :to="'/users/' + item.author.id">{{ item.author.nickname }}</RouterLink>
        <time v-if="item.createdAt" :datetime="item.createdAt">{{ formatDate(item.createdAt) }}</time>
        <span v-if="item.editedAt">已编辑</span>
      </div>
      <p class="community-literal">{{ item.body }}</p>
    </template>
    <p v-else class="muted">{{ item.displayState === 'DELETED' ? '评论已删除' : '评论暂不可查看' }}</p>
    <div class="community-actions community-comment-actions">
      <template v-if="writable && item.displayState === 'VISIBLE'">
        <button v-if="item.capabilities.canReply" type="button" :disabled="busy" @click="$emit('reply', item)">回复</button>
        <button v-if="item.capabilities.canEdit" type="button" :disabled="busy" @click="$emit('edit', item)">编辑</button>
      </template>
      <button v-if="item.displayState !== 'DELETED' && item.capabilities.canDelete" type="button" :disabled="busy" @click="$emit('remove', item)">删除评论</button>
    </div>
  </article>
</template>
