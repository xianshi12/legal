import { defineStore } from 'pinia'
import {
  createSessionApi,
  deleteSessionApi,
  ensureEmptySessionApi,
  historyApi,
  listSessionsApi,
  sendMessageStreamApi,
} from '../api/chat'

function buildPendingAttachments(files) {
  if (!files?.length) return []
  return files.map((f) => ({
    originalName: f.name,
    contentType: f.type || '',
    size: f.size,
    previewUrl:
      f.type?.startsWith('image/') || f.type === 'application/pdf' || f.type === 'text/plain'
        ? URL.createObjectURL(f)
        : '',
  }))
}

function revokeAttachmentBlobs(attachments) {
  if (!Array.isArray(attachments)) return
  for (const a of attachments) {
    if (a?.previewUrl?.startsWith('blob:')) {
      try {
        URL.revokeObjectURL(a.previewUrl)
      } catch {
        /* ignore */
      }
    }
  }
}

export const useChatStore = defineStore('chat', {
  state: () => ({
    sessions: [],
    currentSessionId: '',
    messages: [],
    loading: false,
    creatingSession: false,
    deletingSessionId: '',
    currentScenario: '',
  }),
  actions: {
    normalizeSessions(list) {
      const arr = Array.isArray(list) ? list : []
      return [...arr].sort((a, b) => {
        const ta = new Date(a?.lastMessageTime || a?.createdAt || 0).getTime()
        const tb = new Date(b?.lastMessageTime || b?.createdAt || 0).getTime()
        return tb - ta
      })
    },
    async refreshSessions() {
      const resp = await listSessionsApi()
      const body = resp?.data
      const raw = body?.data
      const list = Array.isArray(raw) ? raw : []
      this.sessions = this.normalizeSessions(list)
    },
    async ensureEntrySession() {
      await this.refreshSessions()
      const resp = await ensureEmptySessionApi()
      const body = resp?.data
      const sid = body?.data?.sessionId ?? body?.sessionId
      await this.refreshSessions()
      this.currentSessionId = sid || this.sessions[0]?.sessionId || ''
      if (this.currentSessionId) {
        await this.loadHistory(this.currentSessionId)
      } else {
        this.messages = []
      }
    },
    async createSession(title) {
      this.creatingSession = true
      try {
        const { data } = await createSessionApi({ title })
        const sid = data?.data?.sessionId ?? data?.sessionId
        await this.refreshSessions()
        if (sid) {
          this.currentSessionId = sid
          await this.loadHistory(sid)
        }
      } finally {
        this.creatingSession = false
      }
    },
    async removeSession(sessionId) {
      if (!sessionId) return
      this.deletingSessionId = sessionId
      try {
        await deleteSessionApi(sessionId)
        const wasCurrent = this.currentSessionId === sessionId
        this.sessions = this.sessions.filter((s) => s.sessionId !== sessionId)

        if (!wasCurrent) {
          await this.refreshSessions()
          return
        }

        this.currentScenario = ''
        const nextSessionId = this.sessions[0]?.sessionId || ''
        if (nextSessionId) {
          this.currentSessionId = nextSessionId
          await this.loadHistory(nextSessionId)
          await this.refreshSessions()
        } else {
          this.currentSessionId = ''
          this.messages = []
          await this.ensureEntrySession()
        }
      } finally {
        this.deletingSessionId = ''
      }
    },
    async selectSession(sessionId) {
      if (!sessionId) return
      this.currentSessionId = sessionId
      await this.loadHistory(sessionId)
    },
    async loadHistory(sessionId) {
      const { data } = await historyApi(sessionId)
      this.messages = data?.data ?? data ?? []
    },
    async sendMessage({ question, files, extractedTexts }) {
      if (!this.currentSessionId) {
        await this.ensureEntrySession()
      }
      const localUserMsgId = `local-user-${Date.now()}`
      const localUserMsg = {
        id: localUserMsgId,
        role: 'user',
        content: question,
        createdAt: new Date().toISOString(),
        attachments: buildPendingAttachments(files),
      }
      this.messages = [localUserMsg, ...this.messages]

      const formData = new FormData()
      formData.append('question', question)
      formData.append('sessionId', this.currentSessionId)
      ;(files || []).forEach((f) => formData.append('files', f))
      if (extractedTexts && extractedTexts.length) {
        formData.append('preExtractedTextsJson', JSON.stringify(extractedTexts))
      }

      this.loading = true
      try {
        const streamMsg = {
          id: `local-ai-stream-${Date.now()}`,
          role: 'assistant',
          content: '',
          streaming: true,
          references: [],
          createdAt: new Date().toISOString(),
        }
        this.messages = [streamMsg, ...this.messages]

        await sendMessageStreamApi(formData, {
          onToken: (token) => {
            const idx = this.messages.findIndex((m) => m.id === streamMsg.id)
            if (idx >= 0) {
              this.messages[idx].content += token
            }
          },
          onDone: (payload) => {
            const done = payload?.data ?? payload ?? {}
            const idx = this.messages.findIndex((m) => m.id === streamMsg.id)
            if (done?.sessionId) {
              this.currentSessionId = done.sessionId
            }
            this.currentScenario = done?.scenario ?? ''
            if (idx >= 0) {
              this.messages[idx] = {
                ...this.messages[idx],
                id: done?.messageId ?? this.messages[idx].id,
                streaming: false,
                content: done?.answer != null && String(done.answer).length > 0 ? done.answer : this.messages[idx].content,
                scenario: done?.scenario ?? '',
                conclusion: done?.conclusion ?? '',
                legalBasis: done?.legalBasis ?? [],
                actionSteps: done?.actionSteps ?? [],
                evidenceChecklist: done?.evidenceChecklist ?? [],
                riskWarnings: done?.riskWarnings ?? [],
                followupQuestions: done?.followupQuestions ?? [],
                references: done?.references ?? [],
              }
            }
            const uidx = this.messages.findIndex((m) => m.id === localUserMsgId)
            if (uidx >= 0) {
              revokeAttachmentBlobs(this.messages[uidx]?.attachments)
              this.messages[uidx] = {
                ...this.messages[uidx],
                id: done?.userMessageId || this.messages[uidx].id,
                attachments: done?.userAttachments ?? this.messages[uidx].attachments,
              }
            }
            if (done?.sessionId && done?.sessionTitle) {
              this.sessions = this.normalizeSessions(
                this.sessions.map((s) =>
                  s.sessionId === done.sessionId
                    ? { ...s, title: done.sessionTitle, lastMessageTime: new Date().toISOString() }
                    : s,
                ),
              )
            }
            this.refreshSessions()
          },
          onError: (msg) => {
            throw new Error(msg || '流式响应错误')
          },
        })
      } catch (err) {
        const uidx = this.messages.findIndex((m) => m.id === localUserMsgId)
        if (uidx >= 0) {
          revokeAttachmentBlobs(this.messages[uidx]?.attachments)
        }
        const assistantMsg = {
          id: `local-ai-error-${Date.now()}`,
          role: 'assistant',
          content: `服务暂时不可用：${err?.message || '未知错误'}\n\n请检查后端服务、数据库及模型配置后重试。`,
          references: [],
          createdAt: new Date().toISOString(),
        }
        this.messages = [assistantMsg, ...this.messages]
      } finally {
        this.loading = false
      }
    },
  },
})
