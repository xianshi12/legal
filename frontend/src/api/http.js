import axios from 'axios'

const SESSION_KEY = 'legalAssistant:authSession'

function readSession() {
  try {
    const raw = sessionStorage.getItem(SESSION_KEY) || localStorage.getItem(SESSION_KEY)
    return raw ? JSON.parse(raw) : null
  } catch {
    return null
  }
}

function clearSession() {
  localStorage.removeItem(SESSION_KEY)
  sessionStorage.removeItem(SESSION_KEY)
}

export const http = axios.create({
  baseURL: '/',
  timeout: 60000,
})

http.interceptors.request.use((config) => {
  const session = readSession()
  if (session?.token) {
    config.headers = config.headers || {}
    config.headers.Authorization = `Bearer ${session.token}`
    config.headers['X-User-Role'] = session.user?.role || ''
  }
  return config
})

http.interceptors.response.use(
  (resp) => resp,
  async (error) => {
    if (error?.response?.status === 401) {
      clearSession()
      if (window.location.pathname !== '/login') {
        window.location.href = `/login?redirect=${encodeURIComponent(window.location.pathname + window.location.search)}`
      }
    }
    const data = error?.response?.data
    if (data instanceof Blob) {
      const ct = String(error?.response?.headers?.['content-type'] || '')
      if (ct.includes('application/json')) {
        try {
          const text = await data.text()
          const j = JSON.parse(text)
          const msg = j.message || j.detail || j.error || text || '请求失败'
          return Promise.reject(new Error(msg))
        } catch {
          return Promise.reject(new Error('请求失败'))
        }
      }
    }
    const msg =
      error?.response?.data?.message ||
      error?.response?.data?.error ||
      error?.message ||
      '请求失败'
    return Promise.reject(new Error(msg))
  },
)

