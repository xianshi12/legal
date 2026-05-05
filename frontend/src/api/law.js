import { http } from './http'

function unwrap(resp) {
  return resp.data?.data ?? resp.data
}

function buildParams(params) {
  const search = new URLSearchParams()
  Object.entries(params || {}).forEach(([key, value]) => {
    if (Array.isArray(value)) {
      value
        .filter((item) => item !== undefined && item !== null && item !== '')
        .forEach((item) => search.append(key, item))
    } else if (value !== undefined && value !== null && value !== '') {
      search.set(key, value)
    }
  })
  return search
}

export async function searchLaws(params) {
  const resp = await http.get('/api/laws/search', { params: buildParams(params) })
  return unwrap(resp)
}

/**
 * @param {number|string} id
 * @param {{ interpretationSource?: 'auto' | 'stored' }} [opts] auto：按来源 URL 截取条文正文并生成解读（可走大模型）；stored：仅库中已存解读
 */
export async function getLawDetail(id, opts = {}) {
  const params = {}
  if (opts.interpretationSource) {
    params.interpretationSource = opts.interpretationSource
  }
  const resp = await http.get(`/api/laws/${id}`, { params: buildParams(params) })
  return unwrap(resp)
}

export async function getLawMeta() {
  const resp = await http.get('/api/laws/meta')
  return unwrap(resp)
}

export async function getLawTags(tagType) {
  const resp = await http.get('/api/laws/tags', { params: buildParams({ tagType }) })
  return unwrap(resp)
}

export async function getLawVersions(id) {
  const resp = await http.get(`/api/laws/${id}/versions`)
  return unwrap(resp)
}

/**
 * @param {string[] | Record<string, unknown>} payload
 *   数组：按 URL 抓取单页；对象：如 `{ provider: 'npc-flk', npcStreamUntilDetailQuota: true, npcPageSize: 20, npcMaxLaws: 1 }` 抓取 1 部新法规（已存在跳过不计入）。
 */
export async function syncLaws(payload = []) {
  const body = Array.isArray(payload)
    ? { urls: payload.filter(Boolean) }
    : typeof payload === 'object' && payload !== null
      ? payload
      : { urls: [] }
  const resp = await http.post('/api/laws/sync', body)
  return unwrap(resp)
}
