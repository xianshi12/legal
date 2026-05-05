import { http } from './http'

function unwrap(resp) {
  return resp.data?.data ?? resp.data
}

export async function getDocumentTemplates() {
  const resp = await http.get('/api/documents/templates')
  return unwrap(resp)
}

export async function generateDocument(payload) {
  const resp = await http.post('/api/documents/generate', payload)
  return unwrap(resp)
}

export async function optimizeDocument(payload) {
  const form = new FormData()
  form.append('documentType', payload.documentType)
  form.append('outputMode', payload.outputMode || 'optimize')
  if (payload.facts) form.append('facts', payload.facts)
  if (payload.extraRequirements) form.append('extraRequirements', payload.extraRequirements)
  if (payload.rawText) form.append('rawText', payload.rawText)
  if (payload.file) form.append('file', payload.file)
  const resp = await http.post('/api/documents/optimize', form, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
  return unwrap(resp)
}

/**
 * 导出正文为 TXT / DOCX / PDF（二进制）。
 * @param {{ format: 'TXT'|'DOCX'|'PDF', title?: string, content: string }} payload
 * @returns {Promise<{ blob: Blob, filename: string }>}
 */
export async function exportDocumentFile(payload) {
  const resp = await http.post(
    '/api/documents/export',
    {
      format: payload.format,
      title: payload.title || '法律文书',
      content: payload.content,
    },
    { responseType: 'blob' },
  )
  const disposition = resp.headers['content-disposition'] || resp.headers['Content-Disposition'] || ''
  const filename = parseContentDispositionFilename(disposition) || defaultExportFilename(payload)
  return { blob: resp.data, filename }
}

function parseContentDispositionFilename(disposition) {
  const mStar = /filename\*=UTF-8''([^;\s]+)/i.exec(disposition)
  if (mStar) {
    try {
      return decodeURIComponent(mStar[1].replace(/\+/g, '%20'))
    } catch {
      return mStar[1]
    }
  }
  const m = /filename="([^"]+)"/i.exec(disposition)
  if (m) return m[1]
  const m2 = /filename=([^;\s]+)/i.exec(disposition)
  return m2 ? m2[1].replace(/"/g, '') : ''
}

function defaultExportFilename(payload) {
  const base = (payload.title || '法律文书').replace(/[\\/:*?"<>|\r\n]/g, '_').slice(0, 80)
  const ext = String(payload.format || 'txt').toLowerCase()
  return `${base || '法律文书'}.${ext}`
}
