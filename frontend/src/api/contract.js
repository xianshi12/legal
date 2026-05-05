import { http } from './http'

/** 含文件解析 + 通义长文本，易超过默认 60s；与后端 LLM 耗时对齐 */
const CONTRACT_REVIEW_TIMEOUT_MS = 180000

function unwrap(resp) {
  return resp.data?.data ?? resp.data
}

export async function reviewContract(payload) {
  const form = new FormData()
  if (payload.contractName) form.append('contractName', payload.contractName)
  if (payload.contractType) form.append('contractType', payload.contractType)
  if (payload.reviewMode) form.append('reviewMode', payload.reviewMode)
  if (payload.reviewFocus) form.append('reviewFocus', payload.reviewFocus)
  if (payload.rawText) form.append('rawText', payload.rawText)
  if (payload.file) form.append('file', payload.file)
  const resp = await http.post('/api/contracts/review', form, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: CONTRACT_REVIEW_TIMEOUT_MS,
  })
  return unwrap(resp)
}
