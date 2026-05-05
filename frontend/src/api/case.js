import { http } from './http'

function unwrap(resp) {
  return resp.data?.data ?? resp.data
}

function buildParams(params) {
  const search = new URLSearchParams()
  Object.entries(params || {}).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') {
      search.set(key, value)
    }
  })
  return search
}

export async function matchCases(params) {
  const resp = await http.get('/api/cases/match', { params: buildParams(params) })
  return unwrap(resp)
}

export async function getCaseMeta() {
  const resp = await http.get('/api/cases/meta')
  return unwrap(resp)
}
