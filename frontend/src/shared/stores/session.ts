import { ref } from 'vue'
import { defineStore } from 'pinia'
import { api } from '../api/client'
import type { AuthPayload, Envelope, User } from '../api/types'

export const useSessionStore = defineStore('session', () => {
  const user = ref<User | null>(null)
  const initialized = ref(false)
  let initializing: Promise<void> | null = null
  api.onExpired(() => { user.value = null })
  api.onSession(payload => { user.value = payload.user })
  async function initialize() {
    if (initialized.value) return
    if (!initializing) initializing = api.refreshSession().then(() => {}).catch(() => {}).finally(() => { initialized.value = true })
    await initializing
  }
  async function login(username: string, password: string) {
    const { data } = await api.http.post<Envelope<AuthPayload>>('/auth/login', { username, password })
    api.setToken(data.data.accessToken)
    user.value = data.data.user
    initialized.value = true
  }
  async function reload() { user.value = (await api.http.get<Envelope<User>>('/me')).data.data }
  async function logout() { await api.http.post('/auth/logout'); api.clear() }
  return { user, initialized, initialize, login, logout, reload }
})
