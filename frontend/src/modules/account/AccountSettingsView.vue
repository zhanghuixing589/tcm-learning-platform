<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from "vue";
import { onBeforeRouteLeave } from "vue-router";
import { api, errorMessage } from "../../shared/api/client";
import type { Envelope } from "../../shared/api/types";
import { useSessionStore } from "../../shared/stores/session";
import type { ProfileSummary } from "./personalCenter";
import AccountIcon from "./AccountIcon.vue";
import UserAvatar from "./UserAvatar.vue";
import "./account.css";
const session = useSessionStore();
const form = reactive({
  nickname: session.user?.nickname || "",
  bio: session.user?.bio || "",
});
const saved = ref(JSON.stringify(form)),
  busy = ref(false),
  loading = ref(true),
  uploading = ref(false),
  error = ref(""),
  success = ref(""),
  avatarUrl = ref<string | null>(null),
  avatarRevision = ref(0);
const loadError = ref(false);
const dirty = computed(() => saved.value !== JSON.stringify(form));
async function load() {
  loading.value = true;
  error.value = "";
  loadError.value = false;
  try {
    await session.reload();
    form.nickname = session.user!.nickname;
    form.bio = session.user!.bio || "";
    saved.value = JSON.stringify(form);
    avatarUrl.value = (
      await api.http.get<Envelope<ProfileSummary>>("/me/profile-summary")
    ).data.data.profile.avatarUrl;
  } catch (e) {
    error.value = errorMessage(e);
    loadError.value = true;
  } finally {
    loading.value = false;
  }
}
async function save() {
  if (busy.value || loading.value) return;
  busy.value = true;
  error.value = "";
  success.value = "";
  try {
    await api.http.patch("/me", {
      nickname: form.nickname.trim(),
      bio: form.bio.trim(),
    });
    await session.reload();
    saved.value = JSON.stringify(form);
    success.value = "个人资料已保存";
  } catch (e) {
    error.value = errorMessage(e);
  } finally {
    busy.value = false;
  }
}
async function upload(event: Event) {
  const input = event.target as HTMLInputElement,
    file = input.files?.[0];
  if (!file || uploading.value) return;
  error.value = "";
  success.value = "";
  if (
    !["image/png", "image/jpeg"].includes(file.type) ||
    file.size > 2 * 1024 * 1024
  ) {
    error.value = "请选择不超过 2MB 的 JPG 或 PNG 图片";
    input.value = "";
    return;
  }
  uploading.value = true;
  try {
    const data = new FormData();
    data.append("file", file);
    avatarUrl.value = (
      await api.http.post<Envelope<{ avatarUrl: string }>>("/me/avatar", data)
    ).data.data.avatarUrl;
    avatarRevision.value++;
    success.value = "头像已更新";
  } catch (e) {
    error.value = errorMessage(e);
  } finally {
    uploading.value = false;
    input.value = "";
  }
}
onBeforeRouteLeave(
  () => !dirty.value || window.confirm("资料还没有保存，确定离开吗？"),
);
function beforeUnload(event: BeforeUnloadEvent) {
  if (dirty.value) {
    event.preventDefault();
    event.returnValue = "";
  }
}
onMounted(() => {
  void load();
  window.addEventListener("beforeunload", beforeUnload);
});
onBeforeUnmount(() => window.removeEventListener("beforeunload", beforeUnload));
</script>
<template>
  <div class="account-subpage">
    <RouterLink class="account-back" to="/account"
      ><AccountIcon name="back" :size="18" />返回个人中心</RouterLink
    >
    <div class="settings-heading">
      <h1>编辑个人资料</h1>
      <p>用一张头像、一句话介绍，让大家认识你。</p>
    </div>
    <p v-if="error" role="alert" class="error-message">
      {{ error
      }}<button v-if="loadError" class="text-button" @click="load">
        重新加载
      </button>
    </p>
    <p v-if="success" role="status" class="success-message">{{ success }}</p>
    <el-skeleton v-if="loading" :rows="6" animated />
    <div v-else-if="!loadError" class="settings-form">
      <div class="settings-avatar">
        <UserAvatar
          :key="avatarRevision"
          :nickname="form.nickname"
          :url="avatarUrl"
          large
        />
        <div>
          <label class="avatar-upload"
            ><AccountIcon name="image" :size="17" />{{
              uploading ? "正在上传…" : "更换头像"
            }}<input
              class="sr-only"
              type="file"
              accept="image/png,image/jpeg"
              :disabled="uploading"
              @change="upload"
          /></label>
          <p>JPG 或 PNG，大小不超过 2MB</p>
        </div>
      </div>
      <el-form label-position="top" :disabled="busy" @submit.prevent="save">
        <el-form-item label="用户名"
          ><span class="settings-username">{{
            session.user?.username
          }}</span></el-form-item
        >
        <el-form-item label="昵称" for="account-nickname"
          ><el-input
            id="account-nickname"
            v-model="form.nickname"
            required
            maxlength="80"
            show-word-limit
        /></el-form-item>
        <el-form-item label="个人简介" for="account-bio"
          ><el-input
            id="account-bio"
            v-model="form.bio"
            type="textarea"
            :rows="5"
            maxlength="500"
            show-word-limit
            placeholder="分享你感兴趣的学习方向"
        /></el-form-item>
        <div class="settings-actions">
          <el-button
            type="primary"
            native-type="submit"
            :loading="busy"
            :disabled="uploading"
            >保存资料</el-button
          ><RouterLink to="/account">取消</RouterLink>
        </div>
      </el-form>
    </div>
  </div>
</template>
