<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { useRouter } from "vue-router";
import { api, errorMessage } from "../../shared/api/client";
import { useSessionStore } from "../../shared/stores/session";
const session = useSessionStore(),
  router = useRouter();
const form = reactive({
  nickname: session.user?.nickname || "",
  bio: session.user?.bio || "",
});
const busy = ref(false),
  error = ref(""),
  success = ref("");
const labels: Record<string, string> = {
  LEARNER: "学习者",
  CONTRIBUTOR: "贡献者",
  REVIEWER: "审核者",
  ADMIN: "管理员",
};
onMounted(async () => {
  try {
    await session.reload();
    form.nickname = session.user!.nickname;
    form.bio = session.user!.bio || "";
  } catch (e) {
    error.value = errorMessage(e);
  }
});
async function save() {
  busy.value = true;
  error.value = "";
  success.value = "";
  try {
    await api.http.patch("/me", form);
    await session.reload();
    success.value = "个人资料已保存";
  } catch (e) {
    error.value = errorMessage(e);
  } finally {
    busy.value = false;
  }
}
async function logout() {
  busy.value = true;
  error.value = "";
  try {
    await session.logout();
    await router.push("/login");
  } catch (e) {
    error.value = errorMessage(e);
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>个人中心</h1>
      <p class="muted">管理你的个人资料与内容贡献资格。</p>
    </div>
    <el-button :disabled="busy" @click="logout">退出登录</el-button>
  </div>
  <p v-if="error" role="alert" class="error-message">{{ error }}</p>
  <p v-if="success" role="status" class="success-message">{{ success }}</p>
  <div class="account-grid">
    <section class="panel">
      <h2>基本资料</h2>
      <el-form @submit.prevent="save" label-position="top" :disabled="busy">
        <el-form-item label="用户名"
          ><span>{{ session.user?.username }}</span></el-form-item
        >
        <el-form-item label="昵称" for="account-nickname"
          ><el-input
            id="account-nickname"
            v-model="form.nickname"
            required
            maxlength="80"
        /></el-form-item>
        <el-form-item label="个人简介" for="account-bio"
          ><el-input
            id="account-bio"
            v-model="form.bio"
            type="textarea"
            :rows="4"
            maxlength="500"
            show-word-limit
            placeholder="分享你感兴趣的学习方向"
        /></el-form-item>
        <el-button type="primary" native-type="submit" :loading="busy"
          >保存资料</el-button
        >
      </el-form>
    </section>
    <aside class="account-aside">
      <h2>我的角色</h2>
      <div class="role-tags">
        <el-tag
          v-for="role in session.user?.roles"
          :key="role"
          effect="plain"
          >{{ labels[role] || role }}</el-tag
        >
      </div>
      <p class="muted">
        贡献者可以参与资料和文章提交。审核与管理权限由平台单独授权。
      </p>
      <RouterLink to="/applications">查看贡献资格 →</RouterLink>
    </aside>
  </div>
</template>
