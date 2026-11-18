<template>
  <a class="skip-link" href="#main">跳到主要内容</a>
  <div class="admin-shell">
    <aside class="admin-sidebar">
      <RouterLink to="/" class="brand">中医科普 · 管理</RouterLink>
      <nav aria-label="后台导航">
        <RouterLink to="/admin/applications">资格申请</RouterLink
        ><RouterLink to="/admin/users">用户管理</RouterLink
        ><RouterLink to="/account">返回个人中心</RouterLink>
      </nav>
    </aside>
    <main id="main" class="admin-main" tabindex="-1"><RouterView /></main>
  </div>
</template>
