import { describe, it, expect } from 'vitest'
import { safeRedirect, canEnter } from './access'

describe('route access', () => {
  it('allows only local redirects outside login and register', () => {
    expect(safeRedirect('https://evil.example')).toBe('/account')
    expect(safeRedirect('//evil.example')).toBe('/account')
    expect(safeRedirect('/login')).toBe('/account')
    expect(safeRedirect('/account')).toBe('/account')
  })
  it('requires authentication and the matching management permission', () => {
    expect(canEnter({ requiresAuth: true }, null)).toBe(false)
    expect(canEnter({ permission: 'system:user:manage' }, { permissions: [] })).toBe(false)
    expect(canEnter({ permission: 'system:user:manage' }, { permissions: ['system:user:manage'] })).toBe(true)
  })
})
