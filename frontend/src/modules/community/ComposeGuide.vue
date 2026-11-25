<script setup lang="ts">
defineProps<{ kind: 'ARTICLE' | 'POST' }>();
</script>
<template>
  <details class="compose-guide" aria-label="写作与发布指引"><summary>写作与发布指引</summary>
    <p class="community-rail-eyebrow">把思考，变成交流</p>
    <h2>{{ kind === 'ARTICLE' ? '写一篇有内容的分享' : '发起一次值得参与的讨论' }}</h2>
    <ol><li><strong>选一个合适的分区</strong><p>让分享更容易遇见同样感兴趣的人。</p></li><li><strong>用标题说清你的主题</strong><p>{{ kind === 'ARTICLE' ? '围绕一个主题展开，并在需要时补充参考文献。' : '说清疑问与已有思考，给交流一个具体的起点。' }}</p></li><li><strong>{{ kind === 'ARTICLE' ? '保存，再提交审核' : '检查，再发布讨论' }}</strong><p>{{ kind === 'ARTICLE' ? '文章可先保存服务器草稿。提交审核后版本冻结，结果与意见会保留。' : '讨论发布后即可阅读与回复。未发布内容仅在当前浏览器保留本地草稿。' }}</p></li></ol>
    <div class="compose-guide-note">分享与讨论帮助彼此理解；正式知识的整理与专业审核，请前往<RouterLink to="/contribute">贡献工作台</RouterLink>。</div>
  </details>
</template>
