<script setup lang="ts">
import { formatDate } from "../account/personalCenter";
import UserAvatar from "../account/UserAvatar.vue";
import CommunityGlyph from "./CommunityGlyph.vue";
import type { Post } from "./types";
import { computed } from "vue";
import { communityExcerpt } from "./reading";
import "./community-experience.css";
const props = defineProps<{ item: Post }>();
const excerpt = computed(() => communityExcerpt(props.item.summary || '', props.item.sourceFormat));
</script>

<template>
  <article class="community-post-card" :class="item.resourceType === 'ARTICLE' ? 'post-card-article' : 'post-card-discussion'">
    <div class="post-card-byline">
      <UserAvatar :nickname="item.owner.nickname" :url="item.owner.avatarUrl" />
      <RouterLink :to="'/users/' + item.owner.id">{{ item.owner.nickname }}</RouterLink>
      <span class="post-card-verb">{{ item.resourceType === 'ARTICLE' ? '分享了文章' : '发起了讨论' }}</span>
      <time :datetime="item.publishedAt">{{ formatDate(item.publishedAt) }}</time>
    </div>
    <div class="post-card-body">
      <div class="post-card-copy">
        <div class="post-card-kicker">
          <span>{{ item.resourceType === 'ARTICLE' ? '科普文章' : '学习讨论' }}</span>
          <span v-if="item.pinned" class="post-pinned">置顶</span>
        </div>
        <h2><RouterLink :to="'/community/posts/' + item.id">{{ item.title }}</RouterLink></h2>
        <p v-if="excerpt" class="post-card-summary">{{ excerpt }}</p>
        <RouterLink v-else class="post-card-open" :to="'/community/posts/' + item.id">{{ item.resourceType === 'ARTICLE' ? '展开阅读' : '看看大家怎么说' }} →</RouterLink>
      </div>
    </div>
    <footer class="post-card-footer">
      <div class="post-card-interactions">
        <RouterLink class="post-comment-link" :to="'/community/posts/' + item.id + '#community-comments-' + item.id"><CommunityGlyph kind="comment" /> {{ item.commentCount }} 条评论</RouterLink>
        <span><CommunityGlyph kind="like" />{{ item.likeCount }} 个赞</span>
        <span :class="{ 'is-active': item.bookmarked }" :title="item.bookmarked ? '已收藏' : '收藏人数'"><CommunityGlyph kind="bookmark" />{{ item.bookmarkCount }} 人收藏</span>
      </div>
      <RouterLink v-if="item.section.code !== 'legacy-content'" class="post-section-label" :to="'/community/sections/' + item.section.id">{{ item.section.name }}</RouterLink>
      <span v-else class="post-section-label">{{ item.section.name }}</span>
    </footer>
  </article>
</template>
