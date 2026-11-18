import { afterEach, describe, it, expect, vi } from "vitest";
import { createApiClient } from "./client";

describe("authenticated API client", () => {
  afterEach(() => vi.unstubAllGlobals());
  it("serializes refresh across separate page clients with the browser lock", async () => {
    let tail: Promise<unknown> = Promise.resolve();
    const lock = vi.fn((_name: string, callback: () => Promise<unknown>) => {
      const next = tail.then(callback);
      tail = next.catch(() => {});
      return next;
    });
    vi.stubGlobal("navigator", { locks: { request: lock } });
    let concurrent = 0,
      peak = 0;
    const adapter = async (config: any) => {
      concurrent++;
      peak = Math.max(peak, concurrent);
      await new Promise((resolve) => setTimeout(resolve, 10));
      concurrent--;
      return {
        status: 200,
        statusText: "OK",
        headers: {},
        data: { data: { accessToken: "fresh", user: { id: "1" } } },
        config,
      };
    };
    await Promise.all([
      createApiClient(adapter).refreshSession(),
      createApiClient(adapter).refreshSession(),
    ]);
    expect(lock).toHaveBeenCalledTimes(2);
    expect(peak).toBe(1);
  });
  it.each([200, 401])(
    "rejects a delayed %i from an earlier account without resending as the new account",
    async (status) => {
      let release!: () => void;
      let started!: () => void;
      const ready = new Promise<void>((resolve) => {
        started = resolve;
      });
      const delayed = new Promise<void>((resolve) => {
        release = resolve;
      });
      const adapter = vi.fn(async (config: any) => {
        if (config.headers.Authorization === "Bearer account-A") {
          started();
          await delayed;
          if (status === 401) throw { response: { status: 401 }, config };
        }
        return {
          status: 200,
          statusText: "OK",
          headers: {},
          data: { data: "old account profile" },
          config,
        };
      });
      const api = createApiClient(adapter);
      api.setToken("account-A");
      const pending = api.http.patch("/me", { nickname: "Account A draft" });
      await ready;
      api.clear();
      api.setToken("account-B");
      release();
      await expect(pending).rejects.toBeDefined();
      expect(adapter).toHaveBeenCalledOnce();
      expect(api.getToken()).toBe("account-B");
    },
  );
  it("shares one refresh across concurrent 401 responses and retries with the new token", async () => {
    let refreshCount = 0;
    const transport = vi.fn(async (config: any) => {
      if (config.url === "/auth/refresh") {
        refreshCount++;
        await new Promise((r) => setTimeout(r, 10));
        return {
          status: 200,
          statusText: "OK",
          headers: {},
          data: { data: { accessToken: "fresh", user: { id: "1" } } },
          config,
        };
      }
      if (config.headers.Authorization !== "Bearer fresh") {
        throw { response: { status: 401 }, config };
      }
      return {
        status: 200,
        statusText: "OK",
        headers: {},
        data: { data: "ok" },
        config,
      };
    });
    const api = createApiClient(transport);
    api.setToken("expired");
    const results = await Promise.all([
      api.http.get("/me"),
      api.http.get("/me/role-applications"),
    ]);
    expect(results.map((r) => r.data.data)).toEqual(["ok", "ok"]);
    expect(refreshCount).toBe(1);
    const refreshConfig = transport.mock.calls.find(
      ([c]) => c.url === "/auth/refresh",
    )![0];
    expect(refreshConfig.headers["X-CSRF-Token"]).toBe("1");
    expect(refreshConfig.headers.Authorization).toBeUndefined();
  });
  it("clears state once and rejects when refresh expires instead of looping", async () => {
    const expired = vi.fn();
    const api = createApiClient(async (config: any) => {
      throw { response: { status: 401 }, config };
    });
    api.onExpired(expired);
    api.setToken("old");
    await expect(api.http.get("/me")).rejects.toBeDefined();
    expect(expired).toHaveBeenCalledOnce();
    expect(api.getToken()).toBeNull();
  });
  it("never refreshes failed login requests or sends stale authorization to auth endpoints", async () => {
    const adapter = vi.fn(async (config: any) => {
      throw { response: { status: 401 }, config };
    });
    const api = createApiClient(adapter);
    api.setToken("old");
    await expect(api.http.post("/auth/login", {})).rejects.toBeDefined();
    expect(adapter).toHaveBeenCalledOnce();
    expect(adapter.mock.calls[0][0].headers.Authorization).toBeUndefined();
  });
  it("reuses a refreshed token for a late 401 from an older request", async () => {
    let count = 0;
    const api = createApiClient(async (config: any) => {
      if (config.url === "/auth/refresh") {
        count++;
        return {
          status: 200,
          statusText: "OK",
          headers: {},
          data: { data: { accessToken: "fresh", user: { id: "1" } } },
          config,
        };
      }
      if (config.headers.Authorization !== "Bearer fresh") {
        if (config.url === "/slow") await new Promise((r) => setTimeout(r, 30));
        throw { response: { status: 401 }, config };
      }
      return {
        status: 200,
        statusText: "OK",
        headers: {},
        data: { data: "ok" },
        config,
      };
    });
    api.setToken("old");
    await Promise.all([api.http.get("/fast"), api.http.get("/slow")]);
    expect(count).toBe(1);
  });
});
