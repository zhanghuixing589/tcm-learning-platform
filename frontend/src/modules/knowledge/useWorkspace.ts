import { onBeforeUnmount, ref } from "vue";
import { errorMessage } from "../../shared/api/client";

/** An obsolete route request must never replace the page the reader opened later. */
export function useWorkspace() {
  const loading = ref(false),
    busy = ref(false),
    error = ref(""),
    actionError = ref(""),
    message = ref("");
  let epoch = 0;
  function reset() {
    epoch++;
    loading.value = false;
    busy.value = false;
    error.value = actionError.value = message.value = "";
  }
  onBeforeUnmount(reset);
  async function load<T>(request: () => Promise<T>, apply: (value: T) => void) {
    const ticket = ++epoch;
    loading.value = true;
    error.value = "";
    message.value = "";
    actionError.value = "";
    busy.value = false;
    try {
      const value = await request();
      if (ticket === epoch) apply(value);
    } catch (e) {
      if (ticket === epoch) error.value = errorMessage(e);
    } finally {
      if (ticket === epoch) loading.value = false;
    }
  }
  async function act<T>(
    request: () => Promise<T>,
    apply: (value: T) => void,
    success = "操作已完成",
  ) {
    if (busy.value) return;
    const ticket = epoch;
    busy.value = true;
    actionError.value = "";
    message.value = "";
    try {
      const value = await request();
      if (ticket === epoch) {
        apply(value);
        message.value = success;
      }
    } catch (e) {
      if (ticket === epoch) actionError.value = errorMessage(e);
    } finally {
      if (ticket === epoch) busy.value = false;
    }
  }
  return { loading, busy, error, actionError, message, load, act, reset };
}
