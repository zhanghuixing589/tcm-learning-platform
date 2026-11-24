import { onBeforeUnmount, type Ref } from "vue";
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute } from "vue-router";
import { canEnter } from "../../router/access";
import { useSessionStore } from "../../shared/stores/session";

export function useUnsavedEditor(dirty: Readonly<Ref<boolean>>, internalRoute?: () => string | null) {
  const session = useSessionStore();
  const route = useRoute();
  const account = session.user?.id;
  const currentAccount = () => account === session.user?.id && canEnter(route.meta, session.user);
  function canLeave() {
    return !currentAccount() || !dirty.value || window.confirm("修改尚未保存，确定离开吗？");
  }
  onBeforeRouteLeave(canLeave);
  onBeforeRouteUpdate((to, from) =>
    to.path === from.path || to.path === internalRoute?.() || canLeave());
  function beforeUnload(event: BeforeUnloadEvent) {
    if (!currentAccount() || !dirty.value) return;
    event.preventDefault();
    event.returnValue = "";
  }
  window.addEventListener("beforeunload", beforeUnload);
  onBeforeUnmount(() => window.removeEventListener("beforeunload", beforeUnload));
  return canLeave;
}
