<script setup lang="ts">
import { reactive, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { api, errorMessage } from "../../shared/api/client";
import { useSessionStore } from "../../shared/stores/session";
import { safeRedirect } from "../../router/access";
import {
  PASSWORD_MIN_LENGTH,
  PASSWORD_MAX_LENGTH,
  REGISTRATION_PASSWORD_PATTERN,
  REGISTRATION_PASSWORD_MESSAGE,
  isValidRegistrationPassword,
} from "./passwordPolicy";
const props = defineProps<{ register: boolean }>();
const route = useRoute(),
  router = useRouter(),
  session = useSessionStore();
const form = reactive({ username: "", password: "", nickname: "" });
const busy = ref(false),
  error = ref("");
watch(
  () => props.register,
  () => {
    error.value = "";
    form.password = "";
  },
);
async function submit() {
  error.value = "";
  if (props.register && !isValidRegistrationPassword(form.password)) {
    error.value = REGISTRATION_PASSWORD_MESSAGE;
    return;
  }
  busy.value = true;
  try {
    if (props.register) {
      await api.http.post("/auth/register", form);
      await router.push({ path: "/login", query: { registered: "1" } });
    } else {
      await session.login(form.username, form.password);
      await router.push(safeRedirect(route.query.redirect));
    }
  } catch (e) {
    error.value = errorMessage(e);
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <section class="form-page">
    <RouterLink to="/" class="back-link">← 返回学习门户</RouterLink>
    <h1>{{ register ? "开启你的学习旅程" : "欢迎回来" }}</h1>
    <p class="muted">
      {{
        register
          ? "创建账号，保存属于自己的学习记录。"
          : "登录后继续管理你的学习账号。"
      }}
    </p>
    <el-alert
      v-if="!register && route.query.registered"
      title="注册成功，请使用新账号登录"
      type="success"
      :closable="false"
      show-icon
    />
    <p v-if="error" role="alert" class="error-message">{{ error }}</p>
    <el-form @submit.prevent="submit" label-position="top" :disabled="busy">
      <el-form-item label="用户名" for="username"
        ><el-input
          id="username"
          v-model="form.username"
          autocomplete="username"
          minlength="3"
          maxlength="64"
          required
          pattern="[A-Za-z0-9_]{3,64}"
          placeholder="3–64位字母、数字或下划线"
      /></el-form-item>
      <el-form-item v-if="register" label="昵称" for="nickname"
        ><el-input
          id="nickname"
          v-model="form.nickname"
          maxlength="80"
          required
          autocomplete="nickname"
          placeholder="你希望大家如何称呼你"
      /></el-form-item>
      <el-form-item label="密码" for="password"
        ><el-input
          id="password"
          v-model="form.password"
          type="password"
          show-password
          :autocomplete="register ? 'new-password' : 'current-password'"
          :minlength="register ? PASSWORD_MIN_LENGTH : 1"
          :maxlength="register ? PASSWORD_MAX_LENGTH : 72"
          :pattern="register ? REGISTRATION_PASSWORD_PATTERN : undefined"
          :aria-describedby="register ? 'password-hint' : undefined"
          required
          :placeholder="
            register ? '10–12位，大写、小写及数字或特殊符号' : '输入密码'
          "
      /></el-form-item>
      <p v-if="register" id="password-hint" class="muted">
        {{ REGISTRATION_PASSWORD_MESSAGE }}
      </p>
      <el-button
        type="primary"
        native-type="submit"
        :loading="busy"
        class="full-width"
        >{{ register ? "创建账号" : "登录" }}</el-button
      >
    </el-form>
    <p class="form-switch">
      {{ register ? "已有账号？" : "还没有账号？" }}
      <RouterLink :to="register ? '/login' : '/register'">{{
        register ? "立即登录" : "创建账号"
      }}</RouterLink>
    </p>
  </section>
</template>
