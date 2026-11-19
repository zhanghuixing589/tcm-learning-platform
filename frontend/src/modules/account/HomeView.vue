<script setup lang="ts">
import { useSessionStore } from "../../shared/stores/session";
const session = useSessionStore();
const modules = ["问答助手", "学习与测验", "个性化推荐", "科普社区"];
</script>
<template>
  <section class="welcome">
    <div>
      <p class="intro-label">中医知识 · 有据可循</p>
      <h1>让每一次学习，<br />都有可靠的起点。</h1>
      <p class="lead">
        从理解一味药，到读懂一张方。这里将连接知识、问答与练习，陪你逐步建立中医药知识体系。
      </p>
      <RouterLink to="/knowledge" class="primary-link"
        >进入知识中心 <span aria-hidden="true">→</span></RouterLink
      >
    </div>
    <aside class="welcome-note">
      <span class="status-dot" aria-hidden="true"></span
      ><strong>从正式知识，追溯可靠来源</strong>
      <p>
        知识中心已开放，可按类型与分类阅读已审核内容，对照药材属性与方剂组成。
      </p>
      <p>
        贡献者可以上传资料、核对原文并整理知识草稿，专业审核后形成正式版本。
      </p>
    </aside>
  </section>
  <section class="module-section">
    <h2>开始学习与贡献</h2>
    <ol class="module-list">
      <li>
        <RouterLink to="/knowledge">中医药知识中心</RouterLink
        ><span class="muted">已开放</span>
      </li>
      <li>
        <RouterLink to="/formulas/compare">方剂比较</RouterLink
        ><span class="muted">已开放</span>
      </li>
      <li>
        <RouterLink to="/contribute">资料与知识贡献</RouterLink
        ><span class="muted">需贡献资格</span>
      </li>
      <li v-if="session.user?.permissions.includes('knowledge:review')">
        <RouterLink to="/reviews">专业审核</RouterLink
        ><span class="muted">审核工作台</span>
      </li>
    </ol>
    <h2 style="margin-top: 40px">后续开放</h2>
    <ol class="module-list">
      <li v-for="name in modules" :key="name">
        <span>{{ name }}</span><span class="muted">尚未开放</span>
      </li>
    </ol>
  </section>
</template>
