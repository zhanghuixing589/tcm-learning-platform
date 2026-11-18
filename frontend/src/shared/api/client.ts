import axios, {
  type AxiosAdapter,
  type AxiosResponse,
  type InternalAxiosRequestConfig,
} from "axios";
import type { AuthPayload, Envelope } from "./types";
type SessionConfig = InternalAxiosRequestConfig & {
  retried?: boolean;
  sessionEpoch?: number;
};

export function createApiClient(adapter?: AxiosAdapter) {
  const options = {
    baseURL: "/api/v1",
    withCredentials: true,
    timeout: 15000,
    ...(adapter ? { adapter } : {}),
  };
  const http = axios.create(options);
  const bare = axios.create(options);
  let token: string | null = null;
  let pending: Promise<AuthPayload> | null = null;
  let expired: () => void = () => {};
  let session: (payload: AuthPayload) => void = () => {};
  let epoch = 0;
  let userId: string | null = null;

  function clear() {
    epoch++;
    token = null;
    userId = null;
    expired();
  }
  function refreshSession(): Promise<AuthPayload> {
    if (!pending) {
      const started = epoch;
      const request = async (): Promise<AxiosResponse<Envelope<AuthPayload>>> => {
        if (started !== epoch)
          throw new Error("会话已变化，请重新发起操作");
        return await bare.post<Envelope<AuthPayload>>("/auth/refresh", null, {
          headers: { "X-CSRF-Token": "1" },
        });
      };
      const response = Promise.resolve(
        typeof navigator !== "undefined" && navigator.locks
          ? navigator.locks.request("tcm-session-refresh", request)
          : request());
      pending = response
        .then((r) => {
          if (started !== epoch) throw new Error("会话已结束");
          if (userId && userId !== r.data.data.user.id)
            throw new Error("登录账号已变化，请重新登录");
          token = r.data.data.accessToken;
          userId = r.data.data.user.id;
          session(r.data.data);
          return r.data.data;
        })
        .catch((e) => {
          if (started === epoch) clear();
          throw e;
        })
        .finally(() => {
          pending = null;
        });
    }
    return pending;
  }
  http.interceptors.request.use((config) => {
    const tracked = config as SessionConfig;
    if (tracked.sessionEpoch !== undefined && tracked.sessionEpoch !== epoch)
      throw new Error("会话已变化，请重新发起操作");
    tracked.sessionEpoch = epoch;
    if (token && !config.url?.startsWith("/auth/"))
      config.headers.Authorization = `Bearer ${token}`;
    if (config.url === "/auth/logout") config.headers["X-CSRF-Token"] = "1";
    return config;
  });
  http.interceptors.response.use(
    (r) => {
      if (
        !r.config.url?.startsWith("/auth/") &&
        (r.config as SessionConfig).sessionEpoch !== epoch
      )
        throw new Error("会话已变化，请重新发起操作");
      return r;
    },
    async (error) => {
      const config = error.config as SessionConfig | undefined;
      if (
        error.response?.status !== 401 ||
        !config ||
        config.url?.startsWith("/auth/")
      )
        throw error;
      if (config.sessionEpoch !== epoch)
        throw new Error("会话已变化，请重新发起操作");
      if (config.retried) {
        clear();
        throw error;
      }
      config.retried = true;
      if (token && config.headers.Authorization !== `Bearer ${token}`)
        return http.request(config);
      await refreshSession();
      return http.request(config);
    },
  );
  return {
    http,
    refreshSession,
    clear,
    getToken: () => token,
    setToken: (value: string | null, accountId?: string) => {
      epoch++;
      token = value;
      userId = accountId || null;
    },
    onExpired: (callback: () => void) => {
      expired = callback;
    },
    onSession: (callback: (payload: AuthPayload) => void) => {
      session = callback;
    },
  };
}
export const api = createApiClient();
export function errorMessage(error: unknown): string {
  if (axios.isAxiosError(error)) {
    const response = error.response?.data as
      Partial<Envelope<unknown>> | undefined;
    if (response?.message)
      return `${response.message}${response.traceId ? `（追踪号 ${response.traceId}）` : ""}`;
    return error.code === "ECONNABORTED"
      ? "请求超时，请稍后重试"
      : "暂时无法连接服务，请检查网络后重试";
  }
  return error instanceof Error ? error.message : "操作失败，请稍后重试";
}
