import { describe, it, expect, vi } from 'vitest'
import { createApiClient } from './client'

describe('authenticated API client', () => {
  it('shares one refresh across concurrent 401 responses and retries with the new token', async () => {
    let refreshCount = 0
    const transport = vi.fn(async (config: any) => {
      if (config.url === '/auth/refresh') {
        refreshCount++
        await new Promise(r => setTimeout(r, 10))
        return { status: 200, data: { data: { accessToken: 'fresh', user: { id: '1' } } }, config }
      }
      if (config.headers.Authorization !== 'Bearer fresh') {
        throw { response: { status: 401 }, config }
      }
      return { status: 200, data: { data: 'ok' }, config }
    })
    const api = createApiClient(transport)
    api.setToken('expired')
    const results = await Promise.all([api.http.get('/me'), api.http.get('/me/role-applications')])
    expect(results.map(r => r.data.data)).toEqual(['ok', 'ok'])
    expect(refreshCount).toBe(1)
    const refreshConfig = transport.mock.calls.find(([c]) => c.url === '/auth/refresh')![0]
    expect(refreshConfig.headers['X-CSRF-Token']).toBe('1')
    expect(refreshConfig.headers.Authorization).toBeUndefined()
  })
  it('clears state once and rejects when refresh expires instead of looping', async () => {
    const expired = vi.fn()
    const api = createApiClient(async (config: any) => { throw { response: { status: 401 }, config } })
    api.onExpired(expired)
    api.setToken('old')
    await expect(api.http.get('/me')).rejects.toBeDefined()
    expect(expired).toHaveBeenCalledOnce()
    expect(api.getToken()).toBeNull()
  })
  it('never refreshes failed login requests or sends stale authorization to auth endpoints', async () => {
    const adapter = vi.fn(async (config: any) => { throw { response: { status: 401 }, config } })
    const api = createApiClient(adapter)
    api.setToken('old')
    await expect(api.http.post('/auth/login', {})).rejects.toBeDefined()
    expect(adapter).toHaveBeenCalledOnce()
    expect(adapter.mock.calls[0][0].headers.Authorization).toBeUndefined()
  })
})
