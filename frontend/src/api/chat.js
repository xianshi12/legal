import { http } from './http'

export function createSessionApi(payload) {
  return http.post('/api/chat/session/create', payload)
}

export function listSessionsApi() {
  return http.get('/api/chat/session/list')
}

export function ensureEmptySessionApi() {
  return http.post('/api/chat/session/ensure-empty')
}

export function deleteSessionApi(sessionId) {
  return http.delete(`/api/chat/session/${encodeURIComponent(sessionId)}`)
}

export function sendMessageApi(formData) {
  return http.post('/api/chat/message/send', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
}

export function historyApi(sessionId) {
  return http.get(`/api/chat/message/history/${encodeURIComponent(sessionId)}`)
}

/** 附件文本预提取（图片：百度千帆 PaddleOCR-VL /v2/ocr/paddleocr；文档：Tika） */
export async function extractChatAttachmentsApi(formData) {
  const resp = await http.post('/api/chat/attachments/extract-text', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
  return resp.data?.data ?? resp.data ?? []
}

export async function sendMessageStreamApi(formData, { onToken, onDone, onError }) {
  const resp = await fetch('/api/chat/message/stream', {
    method: 'POST',
    body: formData,
  })
  if (!resp.ok || !resp.body) {
    throw new Error(`流式请求失败: ${resp.status}`)
  }

  const reader = resp.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''

  while (true) {
    const { value, done } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    const events = buffer.split('\n\n')
    buffer = events.pop() || ''
    for (const raw of events) {
      const lines = raw.split('\n')
      let eventName = 'message'
      const dataLines = []
      for (const line of lines) {
        if (line.startsWith('event:')) {
          eventName = line.slice(6).trim()
        } else if (line.startsWith('data:')) {
          dataLines.push(line.slice(5).replace(/^ /, ''))
        }
      }
      const data = dataLines.join('\n')
      if (eventName === 'token') {
        onToken?.(data)
      } else if (eventName === 'done') {
        try {
          onDone?.(JSON.parse(data))
        } catch {
          onDone?.(null)
        }
      } else if (eventName === 'error') {
        onError?.(data || '流式响应错误')
      }
    }
  }
}
