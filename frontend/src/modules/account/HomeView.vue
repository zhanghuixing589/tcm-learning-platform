<script setup lang="ts">
import { useSessionStore } from "../../shared/stores/session";
const session = useSessionStore();
const modules = [
  "知识库中心",
  "中医药知识中心",
  "问答助手",
  "学习与测验",
  "个性化推荐",
  "科普社区",
];
</script>
<template>
  <section class="welcome">
    <div>
      <p class="intro-label">中医知识 · 有据可循</p>
      <h1>让每一次学习，<br />都有可靠的起点。</h1>
      <p class="lead">
        从理解一味药，到读懂一张方。这里将连接知识、问答与练习，陪你逐步建立中医药知识体系。
      </p>
      <RouterLink
        :to="session.user ? '/account' : '/register'"
        class="primary-link"
        >{{ session.user ? "进入个人中心" : "创建学习账号" }}
        <span aria-hidden="true">→</span></RouterLink
      >
    </div>
    <aside class="welcome-note">
      <span class="status-dot" aria-hidden="true"></span
      ><strong>平台正在逐步开放</strong>
      <p>现在可以创建账号、完善个人资料，申请成为内容贡献者。</p>
      <p>知识浏览、智能问答和学习练习将在后续开放。</p>
    </aside>
  </section>
  <section class="module-section">
    <h2>围绕学习，连接六个方向</h2>
    <ol class="module-list">
      <li v-for="name in modules" :key="name">
        <span>{{ name }}</span
        ><span class="muted">尚未开放</span>
      </li>
    </ol>
  </section>
</template>
