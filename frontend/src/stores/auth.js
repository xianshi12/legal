import { defineStore } from 'pinia'
import { login, register as registerAccount, smsLogin } from '../api/auth'

const SESSION_KEY = 'legalAssistant:authSession'

function readJson(key, fallback) {
  try {
    const raw = sessionStorage.getItem(key) || localStorage.getItem(key)
    return raw ? JSON.parse(raw) : fallback
  } catch {
    return fallback
  }
}

function writeSession(session) {
  localStorage.removeItem(SESSION_KEY)
  sessionStorage.removeItem(SESSION_KEY)
  if (!session) return
  const target = session.remember ? localStorage : sessionStorage
  target.setItem(SESSION_KEY, JSON.stringify(session))
}

export const useAuthStore = defineStore('auth', {
  state: () => ({
    session: readJson(SESSION_KEY, null),
  }),

  getters: {
    isAuthenticated: (state) => Boolean(state.session?.token && state.session?.user),
    token: (state) => state.session?.token || '',
    user: (state) => state.session?.user || null,
    role: (state) => state.session?.user?.role || '',
    roleName: (state) => (state.session?.user?.role === 'lawyer' ? '律师' : '用户'),
  },

  actions: {
    persistSession(session) {
      this.session = session
      writeSession(session)
    },

    async register(payload) {
      const data = await registerAccount(payload)
      this.persistSession({
        token: data.token,
        user: data.user,
        remember: Boolean(payload.remember),
        loginAt: new Date().toISOString(),
      })
      return data
    },

    async loginWithPassword(payload) {
      const data = await login({
        role: payload.role,
        phone: payload.phone,
        password: payload.password,
        remember: payload.remember,
      })
      this.persistSession({
        token: data.token,
        user: data.user,
        remember: Boolean(payload.remember),
        loginAt: new Date().toISOString(),
      })
      return data
    },

    async loginWithSms(payload) {
      const data = await smsLogin({
        role: payload.role,
        phone: payload.phone,
        code: payload.code,
        remember: payload.remember,
      })
      this.persistSession({
        token: data.token,
        user: data.user,
        remember: Boolean(payload.remember),
        loginAt: new Date().toISOString(),
      })
      return data
    },

    logout() {
      this.persistSession(null)
    },

    canAccess(route) {
      const allowedRoles = route.meta?.roles
      if (!allowedRoles?.length) return true
      return allowedRoles.includes(this.role)
    },
  },
})
