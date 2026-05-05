import { http } from './http'

function unwrap(resp) {
  return resp.data?.data ?? resp.data
}

export async function getGuideTemplates() {
  const resp = await http.get('/api/guides/templates')
  return unwrap(resp)
}

export async function generateGuide(payload) {
  const resp = await http.post('/api/guides/generate', payload)
  return unwrap(resp)
}

export async function calculateLimitation(params) {
  const resp = await http.get('/api/guides/limitation', { params })
  return unwrap(resp)
}
