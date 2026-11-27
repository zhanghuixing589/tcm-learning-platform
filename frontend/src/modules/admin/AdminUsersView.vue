<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from "vue";
import { api, errorMessage } from "../../shared/api/client";
import type { Envelope, Page, User } from "../../shared/api/types";
import { useSessionStore } from "../../shared/stores/session";
const session = useSessionStore();
const items = ref<User[]>([]),
  loading = ref(true),
  busy = ref(false),
  error = ref(""),
  success = ref(""),
  page = ref(1),
  total = ref(0);
const selected = ref<User | null>(null),
  selectedRoles = ref<string[]>([]),
  dialog = ref(false);
const selectedUsername=computed(()=>selected.value?.username ?? '');
const roleError=ref(''),roleErrorElement=ref<HTMLElement|null>(null);
function isActive(user:User){return user.status==='ACTIVE';}
function userStatus(user:User){return isActive(user)?'正常':'已禁用';}
const labels: Record<string, string> = {
  LEARNER: "学习者",
  CONTRIBUTOR: "贡献者",
  REVIEWER: "审核者",
  ADMIN: "管理员",
};
async function load() {
  loading.value = true;
  error.value = "";
  try {
    const { data } = await api.http.get<Envelope<Page<User>>>("/admin/users", {
      params: { page: page.value },
    });
    items.value = data.data.items;
    total.value = data.data.total;
  } catch (e) {
    error.value = errorMessage(e);
  } finally {
    loading.value = false;
  }
}
onMounted(load);
async function changeStatus(user: User) {
  busy.value = true;
  error.value = "";
  success.value = "";
  try {
    await api.http.patch(`/admin/users/${user.id}/status`, {
      status: user.status === "ACTIVE" ? "DISABLED" : "ACTIVE",
    });
    await load();
    success.value = "账号状态已更新";
  } catch (e) {
    error.value = errorMessage(e);
  } finally {
    busy.value = false;
  }
}
function select(user: User) {
  roleError.value='';
  selected.value = user;
  selectedRoles.value = [...user.roles];
  dialog.value = true;
}
async function saveRoles() {
  if (!selected.value) return;
  busy.value = true;
  error.value = "";
  roleError.value='';
  success.value = "";
  try {
    await api.http.put(`/admin/users/${selected.value.id}/roles`, {
      roles: selectedRoles.value,
    });
    dialog.value = false;
    await load();
    success.value = "角色权限已更新";
  } catch (e) {
    roleError.value=errorMessage(e);await nextTick();
    roleErrorElement.value?.scrollIntoView?.({block:'nearest'});
    roleErrorElement.value?.focus({preventScroll:true});
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>用户管理</h1>
      <p class="muted">管理账号状态，按职责授予角色。</p>
    </div>
    <el-button :loading="loading" @click="load">刷新列表</el-button>
  </div>
  <p v-if="error" class="error-message" role="alert">{{ error }}</p>
  <p v-if="success" class="success-message" role="status">{{ success }}</p>
  <el-skeleton v-if="loading" :rows="5" animated /><el-table
    v-else
    :data="items"
    empty-text="暂无用户"
    class="data-table"
    ><el-table-column
      prop="username"
      show-overflow-tooltip
      label="用户名"
      min-width="150"
    /><el-table-column
      prop="nickname"
      show-overflow-tooltip
      label="昵称"
      min-width="120"
    /><el-table-column label="角色" min-width="230"
      ><template #default="{ row }"
        ><div class="role-tags">
          <el-tag v-for="role in row.roles" :key="role" effect="plain">{{
            labels[role]
          }}</el-tag>
        </div></template
      ></el-table-column
    ><el-table-column label="状态" width="90"
      ><template #default="{ row }">{{
        userStatus(row as User)
      }}</template></el-table-column
    ><el-table-column label="操作" min-width="200"
      ><template #default="{ row }"
        ><template v-if="row.id !== session.user?.id"
          ><el-button
            v-if="session.user?.permissions.includes('system:role:manage')"
            link
            type="primary"
            :disabled="busy"
            @click="select(row as User)"
            >角色设置</el-button
          ><el-popconfirm
            :title="
              isActive(row as User)
                ? '禁用后该用户的登录会话将立即失效，确认？'
                : '确认恢复该账号？'
            "
            confirm-button-text="确认"
            cancel-button-text="取消"
            width="260"
            @confirm="changeStatus(row as User)"
            ><template #reference
              ><el-button
                link
                :type="isActive(row as User) ? 'danger' : 'primary'"
                :disabled="busy"
                >{{
                  isActive(row as User) ? "禁用账号" : "恢复账号"
                }}</el-button
              ></template
            ></el-popconfirm
          ></template
        ><span v-else class="muted">当前账号</span></template
      ></el-table-column
    ></el-table
  >
  <el-pagination
    v-if="total > 20"
    v-model:current-page="page"
    layout="prev, pager, next"
    :total="total"
    :page-size="20"
    @current-change="load"
  />
  <el-dialog
    v-model="dialog"
    title="角色设置"
    width="min(520px, 92vw)"
    :close-on-click-modal="!busy"
    :close-on-press-escape="!busy"
    :show-close="!busy"
    ><p>{{ selectedUsername }}</p>
    <p class="muted">管理员与专业审核者是独立角色，请按职责分别授权。</p>
    <p v-if="roleError" ref="roleErrorElement" class="error-message" role="alert" tabindex="-1">{{ roleError }}</p>
    <el-checkbox-group
      v-model="selectedRoles"
      :disabled="busy"
      aria-label="用户角色"
      ><el-checkbox
        v-for="(label, code) in labels"
        :key="code"
        :value="code"
        :disabled="code === 'LEARNER'"
        >{{ label }}</el-checkbox
      ></el-checkbox-group
    ><template #footer
      ><el-button :disabled="busy" @click="dialog = false">取消</el-button
      ><el-button type="primary" :loading="busy" @click="saveRoles"
        >保存角色</el-button
      ></template
    ></el-dialog
  >
</template>
