import axios from 'axios'

export const http = axios.create({
  baseURL: '/',
  timeout: 60000,
})

http.interceptors.response.use(
  (resp) => resp,
  async (error) => {
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

