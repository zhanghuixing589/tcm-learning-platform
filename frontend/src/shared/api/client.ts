import axios, { type AxiosAdapter, type InternalAxiosRequestConfig } from 'axios'
import type { AuthPayload, Envelope } from './types'

export function createApiClient(adapter?: AxiosAdapter) {
  const options = { baseURL: '/api/v1', withCredentials: true, timeout: 15000, ...(adapter ? { adapter } : {}) }
  const http = axios.create(options)
  const bare = axios.create(options)
  let token: string | null = null
  let pending: Promise<AuthPayload> | null = null
  let expired: () => void = () => {}
  let session: (payload: AuthPayload) => void = () => {}
  let epoch = 0

  function clear() { epoch++; token = null; expired() }
  function refreshSession(): Promise<AuthPayload> {
    if (!pending) {
      const started = epoch
      pending = bare.post<Envelope<AuthPayload>>('/auth/refresh', null, { headers: { 'X-CSRF-Token': '1' } })
        .then(r => {
          if (started !== epoch) throw new Error('会话已结束')
          token = r.data.data.accessToken
          session(r.data.data)
          return r.data.data
        })
        .catch(e => { if (started === epoch) clear(); throw e })
        .finally(() => { pending = null })
    }
    return pending
  }
  http.interceptors.request.use(config => {
    if (token && !config.url?.startsWith('/auth/')) config.headers.Authorization = `Bearer ${token}`
    if (config.url === '/auth/logout') config.headers['X-CSRF-Token'] = '1'
    return config
  })
  http.interceptors.response.use(r => r, async error => {
    const config = error.config as (InternalAxiosRequestConfig & { retried?: boolean }) | undefined
    if (error.response?.status !== 401 || !config || config.url?.startsWith('/auth/')) throw error
    if (config.retried) { clear(); throw error }
    config.retried = true
    await refreshSession()
    return http.request(config)
  })
  return { http, refreshSession, clear, getToken: () => token,
    setToken: (value: string | null) => { epoch++; token = value },
    onExpired: (callback: () => void) => { expired = callback },
    onSession: (callback: (payload: AuthPayload) => void) => { session = callback } }
}
export const api = createApiClient()
export function errorMessage(error: unknown): string {
  if (axios.isAxiosError(error)) {
    const response = error.response?.data as Partial<Envelope<unknown>> | undefined
    if (response?.message) return `${response.message}${response.traceId ? `（追踪号 ${response.traceId}）` : ''}`
    return error.code === 'ECONNABORTED' ? '请求超时，请稍后重试' : '暂时无法连接服务，请检查网络后重试'
  }
  return error instanceof Error ? error.message : '操作失败，请稍后重试'
}
