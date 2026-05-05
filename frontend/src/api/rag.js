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

export async function getRagMeta() {
  const resp = await http.get('/api/rag/meta')
  return unwrap(resp)
}

export async function listRagDocuments(params = {}) {
  const resp = await http.get('/api/rag/documents', { params: buildParams(params) })
  return unwrap(resp)
}

export async function uploadRagDocument(formData) {
  const resp = await http.post('/api/rag/documents/upload', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
  return unwrap(resp)
}

export async function ingestRagText(payload) {
  const resp = await http.post('/api/rag/documents/text', payload)
  return unwrap(resp)
}

export async function deleteRagDocument(docId) {
  const resp = await http.delete(`/api/rag/documents/${encodeURIComponent(docId)}`)
  return unwrap(resp)
}

export async function searchRag(params = {}) {
  const resp = await http.get('/api/rag/search', { params: buildParams(params) })
  return unwrap(resp)
}

export async function answerRag(payload) {
  const resp = await http.post('/api/rag/answer', payload)
  return unwrap(resp)
}

export async function bootstrapRagFoundation() {
  const resp = await http.post('/api/rag/bootstrap-foundation')
  return unwrap(resp)
}
