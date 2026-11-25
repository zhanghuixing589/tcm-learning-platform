<script setup lang="ts">
import { computed, ref } from "vue";
import { useRouter } from "vue-router";
import { useSessionStore } from "../../shared/stores/session";
import BotanicalPlate from "../../app/ui/BotanicalPlate.vue";
import ContentGlyph from "../../app/ui/ContentGlyph.vue";
import HomeContent from "./HomeContent.vue";
import "./home.css";
const router = useRouter(), session = useSessionStore();
const query = ref("");
function search() {
  void router.push({ path: "/knowledge", query: query.value.trim() ? { q: query.value.trim() } : {} });
}
const contribution = computed(() => !session.user
  ? { to: "/login?redirect=/applications", label: "登录，了解贡献资格", hint: "登录后查看贡献资格与申请进度。" }
  : session.user.permissions.includes("contribution:submit")
    ? { to: "/contribute", label: "参与知识共建", hint: "进入工作台，整理来源与知识草稿。" }
    : { to: "/applications", label: "查看贡献资格", hint: "提交知识需要贡献资格，可查看资格与申请进度。" });
const paths = [
  { kind: "HERB", label: "中药材知识", title: "认识一味本草", text: "从名称、药性到来源，建立清晰的知识脉络。", to: "/knowledge?type=HERB" },
  { kind: "FORMULA", label: "方剂知识", title: "读懂一张方剂", text: "对照组成与出处，理解方剂之间的联系。", to: "/knowledge?type=FORMULA" },
  { kind: "CONCEPT", label: "中医概念", title: "理解一个概念", text: "从基本概念出发，逐步梳理中医知识。", to: "/knowledge?type=CONCEPT" },
  { kind: "POST", label: "科普社区", title: "交流一种见解", text: "在有据可循的讨论中，让理解更进一步。", to: "/community" },
];
</script>
<template>
  <div class="home-page">
    <section class="home-hero" aria-labelledby="home-title">
      <div class="hero-copy">
        <p class="eyebrow"><span></span>中医药知识 · 有据可循</p>
        <h1 id="home-title">走近本草，<br />让知识<span>生根。</span></h1>
        <p class="hero-lead">从理解一味药，到读懂一张方。<br />在可追溯的知识与真诚的交流中，慢慢建立自己的知识体系。</p>
        <form class="home-search" role="search" aria-label="知识搜索" @submit.prevent="search">
          <label class="home-search-label" for="home-knowledge-query">搜索知识</label>
          <div class="home-search-control"><input id="home-knowledge-query" v-model="query" type="search" maxlength="100" placeholder="搜索规范名称、别名或标题" /><button type="submit">搜索 <span aria-hidden="true">→</span></button></div>
        </form>
        <div class="hero-actions"><RouterLink to="/knowledge" class="secondary-link hero-catalog-link">探索知识中心 <span aria-hidden="true">↗</span></RouterLink><RouterLink to="/community" class="secondary-link">逛逛科普社区 <span aria-hidden="true">→</span></RouterLink></div>
        <div class="hero-proof"><span>审核后发布</span><span>原文可追溯</span><span>持续共建</span></div>
      </div>
      <BotanicalPlate />
    </section>
    <section class="home-paths" aria-labelledby="paths-title">
      <div class="section-heading"><div><p class="eyebrow">从好奇开始</p><h2 id="paths-title">循着兴趣，找到学习的起点</h2></div><RouterLink to="/knowledge">全部知识 →</RouterLink></div>
      <nav class="path-grid" aria-label="学习入口"><RouterLink v-for="path in paths" :key="path.kind" :to="path.to" class="learning-path">
        <div class="path-top"><ContentGlyph :kind="path.kind" /><span class="path-label">{{ path.label }} <span aria-hidden="true">↗</span></span></div>
        <h3>{{ path.title }}</h3><p>{{ path.text }}</p>
      </RouterLink></nav>
    </section>
    <HomeContent />
    <section class="home-contribute"><div><p class="eyebrow">让理解，连接更多人</p><h2>每一份可靠的分享，都让知识更丰盈。</h2><p>上传来源、核对原文、整理知识。让你的学习积累，成为他人探索的起点。</p></div><div class="home-contribute-action"><RouterLink :to="contribution.to" class="primary-link">{{ contribution.label }} <span aria-hidden="true">↗</span></RouterLink><p>{{ contribution.hint }}</p></div></section>
    <section class="home-future" aria-labelledby="future-title"><h2 id="future-title">更多学习方式，正在生长</h2><p>问答助手 · 学习与测验 · 个性化推荐 · 个人知识库 <span>尚未开放</span></p></section>
  </div>
</template>
