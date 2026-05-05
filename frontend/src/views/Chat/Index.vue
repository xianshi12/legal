<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import AppLayout from '../../components/common/AppLayout.vue'
import MarkdownViewer from '../../components/common/MarkdownViewer.vue'
import { useChatStore } from '../../stores/chat'
import { extractChatAttachmentsApi } from '../../api/chat'
import { ElMessage, ElMessageBox } from 'element-plus'

const chat = useChatStore()

const input = ref('')
const uploadingFiles = ref([])
const filesReading = ref(false)
/** 串行执行多次选择/拖放，避免并发读取交错 */
let ingestChain = Promise.resolve()
const fileInputRef = ref()
const streamRef = ref()
const acceptTypes = '.png,.jpg,.jpeg,.webp,.bmp,.pdf,.txt,.doc,.docx'

const currentTitle = computed(() => {
  const s = chat.sessions.find((x) => x.sessionId === chat.currentSessionId)
  return s?.title || '智能咨询'
})

const sessionsView = computed(() => chat.sessions)
const showWelcome = computed(() => chat.messages.length === 0)
const showMobileHistory = ref(false)
/** 全屏附件预览：null 表示关闭 */
const attPreview = ref(null)

function openAttPreview(a) {
  const href = attachmentHref(a)
  if (!href || href === '#') {
    ElMessage.warning('暂无法预览该附件')
    return
  }
  let kind = 'other'
  if (isImageAtt(a)) kind = 'image'
  else if (isPdfAtt(a)) kind = 'pdf'
  attPreview.value = {
    title: a.originalName || '附件',
    href,
    kind,
  }
}

function closeAttPreview() {
  attPreview.value = null
}

function attPreviewKeydown(e) {
  if (e.key === 'Escape') closeAttPreview()
}

watch(attPreview, (v) => {
  if (v) {
    document.addEventListener('keydown', attPreviewKeydown)
    document.body.style.overflow = 'hidden'
  } else {
    document.removeEventListener('keydown', attPreviewKeydown)
    document.body.style.overflow = ''
  }
})

onUnmounted(() => {
  document.removeEventListener('keydown', attPreviewKeydown)
  document.body.style.overflow = ''
})

function formatSessionTime(value) {
  if (!value) return ''
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return String(value)
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  const hh = String(d.getHours()).padStart(2, '0')
  const mm = String(d.getMinutes()).padStart(2, '0')
  const ss = String(d.getSeconds()).padStart(2, '0')
  return `${y}-${m}-${day}T${hh}:${mm}:${ss}`
}

function hasStructuredBlock(m) {
  return Boolean(
    m?.conclusion ||
      (m?.legalBasis && m.legalBasis.length) ||
      (m?.actionSteps && m.actionSteps.length) ||
      (m?.evidenceChecklist && m.evidenceChecklist.length) ||
      (m?.riskWarnings && m.riskWarnings.length) ||
      (m?.followupQuestions && m.followupQuestions.length),
  )
}

function userHasAttachments(m) {
  return Array.isArray(m?.attachments) && m.attachments.length > 0
}

function isImageAtt(a) {
  const c = (a?.contentType || '').toLowerCase()
  return c.startsWith('image/')
}

function isPdfAtt(a) {
  return (a?.contentType || '').toLowerCase() === 'application/pdf'
}

/** 发送前 blob 预览或历史记录中的服务端 URL */
function attachmentHref(a) {
  if (a?.previewUrl) return a.previewUrl
  const sid = chat.currentSessionId
  if (!a?.fileId || !sid) return '#'
  const qs = new URLSearchParams({ sessionId: sid })
  return `/api/chat/files/${encodeURIComponent(a.fileId)}?${qs.toString()}`
}

function userAttChipIcon(a) {
  if (isImageAtt(a)) return 'image'
  if (isPdfAtt(a)) return 'picture_as_pdf'
  return 'draft'
}

function onComposerKeydown(e) {
  if (e.key !== 'Enter') return
  if (e.shiftKey) return
  if (e.isComposing) return
  e.preventDefault()
  send()
}

async function send() {
  const q = input.value.trim()
  if (!q && !uploadingFiles.value.length) return
  const fs = uploadingFiles.value.map((x) => x.file).filter(Boolean)
  const extractedTexts = uploadingFiles.value.map((x) => x.extractedText || '')
  uploadingFiles.value = []
  input.value = ''
  await chat.sendMessage({
    question: q || '请识别并分析我上传的附件内容。',
    files: fs,
    extractedTexts,
  })
}

async function scrollToBottom() {
  await nextTick()
  const el = streamRef.value
  if (!el) return
  el.scrollTop = el.scrollHeight
}

function pickFiles() {
  fileInputRef.value?.click()
}

function validateFile(file) {
  const okTypes = [
    'image/png',
    'image/jpeg',
    'image/webp',
    'image/bmp',
    'application/pdf',
    'text/plain',
    'application/msword',
    'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  ]
  const ok = okTypes.includes(file.type) || /\.(png|jpe?g|webp|bmp|pdf|txt|docx?)$/i.test(file.name)
  if (!ok) {
    ElMessage.error(`${file.name} 类型暂不支持`)
    return false
  }
  const max = 20 * 1024 * 1024
  if (file.size > max) {
    ElMessage.error(`${file.name} 超过 20MB`)
    return false
  }
  return true
}

function ingestFiles(files) {
  const arr = Array.from(files || []).filter(Boolean).filter(validateFile)
  if (!arr.length) return Promise.resolve()
  ingestChain = ingestChain.then(() => doIngestFiles(arr))
  return ingestChain
}

async function doIngestFiles(arr) {
  filesReading.value = true
  try {
    const form = new FormData()
    arr.forEach((f) => form.append('files', f))
    const results = await extractChatAttachmentsApi(form)
    const added = arr.map((f, i) => {
      const r = results[i] ?? {}
      const success = r.ok !== false
      return {
        id: `${Date.now()}-${i}-${f.name}-${Math.random().toString(36).slice(2, 9)}`,
        name: f.name,
        file: f,
        extractedText: success ? String(r.text || '').trim() : '',
        extractError: success ? '' : (r.error || '识别失败'),
      }
    })
    uploadingFiles.value = [...uploadingFiles.value, ...added]
    if (added.some((x) => x.extractError)) {
      ElMessage.warning('部分附件未能识别，发送时将尝试重新解析')
    }
  } catch (err) {
    ElMessage.error(err?.message || '附件识别失败')
  } finally {
    filesReading.value = false
  }
}

function onFileChange(e) {
  const files = Array.from(e.target.files || [])
  e.target.value = ''
  ingestFiles(files)
}

function onDrop(e) {
  e.preventDefault()
  ingestFiles(Array.from(e.dataTransfer?.files || []))
}

function onDragOver(e) {
  e.preventDefault()
}

/** 在输入框内粘贴截图/图片文件时，走与上传相同的 OCR 流程 */
function onComposerPaste(e) {
  const items = e.clipboardData?.items
  if (!items?.length) return
  const files = []
  for (let i = 0; i < items.length; i++) {
    const it = items[i]
    if (it.kind !== 'file') continue
    const f = it.getAsFile()
    if (f) files.push(f)
  }
  if (!files.length) return
  e.preventDefault()
  ingestFiles(files)
}

async function removeSession(sessionId) {
  await ElMessageBox.confirm('确认删除该会话？', '提示', {
    type: 'warning',
    confirmButtonText: '删除',
    cancelButtonText: '取消',
  })
  await chat.removeSession(sessionId)
}

onMounted(async () => {
  await chat.ensureEntrySession()
  await scrollToBottom()
})

watch(
  () => chat.messages.length,
  async () => {
    await scrollToBottom()
  },
)

watch(
  () => chat.messages[0]?.content,
  async () => {
    await scrollToBottom()
  },
)
</script>

<template>
  <AppLayout>
    <main class="main">
      <!-- Session History Sidebar (Inner Left Pane) -->
      <aside :class="['history', showMobileHistory ? 'show' : '']">
        <div class="history-top">
          <button class="ghost-btn" type="button" :disabled="chat.creatingSession" @click="chat.createSession('新会话')">
            <span class="material-symbols-outlined">chat_add_on</span>
            {{ chat.creatingSession ? '创建中...' : '开启新对话' }}
          </button>
        </div>
        <div class="history-list custom-scrollbar">
          <h3 class="history-cap">最近咨询</h3>

          <div
            v-for="s in sessionsView"
            :key="s.sessionId"
            class="history-item group"
            :class="chat.currentSessionId === s.sessionId ? 'active' : ''"
            @click="chat.sessions.length ? (chat.selectSession(s.sessionId), (showMobileHistory = false)) : void 0"
          >
            <div class="history-meta">
              <p class="history-title">{{ s.title || '未命名会话' }}</p>
              <p class="history-time">{{ formatSessionTime(s.createdAt || s.lastMessageTime) }}</p>
            </div>
            <button class="history-del" type="button" title="删除" @click.stop="removeSession(s.sessionId)">
              <span v-if="chat.deletingSessionId !== s.sessionId" class="material-symbols-outlined">delete</span>
              <span v-else class="material-symbols-outlined">hourglass_empty</span>
            </button>
          </div>
        </div>
      </aside>

      <!-- Main Chat Interface -->
      <section class="chat">
        <!-- Chat Header -->
        <header class="chat-head">
          <div class="chat-head-left">
            <button class="mobile-history-btn" type="button" @click="showMobileHistory = !showMobileHistory">
              <span class="material-symbols-outlined">menu</span>
            </button>
            <span class="material-symbols-outlined">gavel</span>
            <h1 class="chat-head-title">智能法律咨询</h1>
          </div>
        </header>

        <!-- Chat Stream -->
        <div
          ref="streamRef"
          :class="['stream', 'custom-scrollbar', showWelcome && 'stream--welcome']"
        >
          <div class="stream-inner">
            <template v-if="showWelcome">
              <div class="welcome">
                <div class="welcome-title">你好，我是法通 AI 助手</div>
                <div class="welcome-sub">你可以输入你的法律问题，我会给出通俗解读、维权步骤和风险提示。</div>
              </div>
            </template>

            <template v-else>
              <div v-for="m in [...chat.messages].reverse()" :key="m.id">
                <!-- user -->
                <div v-if="m.role === 'user'" class="row row-user">
                  <div class="bubble-user">
                    <div v-if="userHasAttachments(m)" class="user-attachments">
                      <div
                        v-for="(a, ai) in m.attachments"
                        :key="a.fileId || a.previewUrl || `${ai}-${a.originalName}`"
                        class="user-att-card"
                      >
                        <button type="button" class="user-att-chip" @click="openAttPreview(a)">
                          <span class="material-symbols-outlined user-att-chip-icon">{{
                            userAttChipIcon(a)
                          }}</span>
                          <span class="user-att-chip-name">{{ a.originalName }}</span>
                          <span class="material-symbols-outlined user-att-chip-open">open_in_full</span>
                          <span class="user-att-chip-hint">全屏预览</span>
                        </button>
                      </div>
                    </div>
                    <p class="text-md">{{ m.content }}</p>
                  </div>
                </div>

                <!-- assistant -->
                <div v-else class="row row-ai">
                  <div class="ai-wrap">
                    <div class="ai-avatar">
                      <span class="material-symbols-outlined">smart_toy</span>
                    </div>
                    <div class="bubble-ai">
                      <div v-if="m.streaming" class="streaming-tip">AI 正在回复...</div>
                      <MarkdownViewer :content="m.content" />

                      <div v-if="hasStructuredBlock(m)" class="structured">
                        <div v-if="m.conclusion" class="section">
                          <h5>结论</h5>
                          <p class="structured-plain">{{ m.conclusion }}</p>
                        </div>
                        <div v-if="m.legalBasis?.length" class="section">
                          <h5>法律依据</h5>
                          <ol>
                            <li v-for="(s, i) in m.legalBasis" :key="`lb-${i}`" class="structured-li">{{ s }}</li>
                          </ol>
                        </div>
                        <div v-if="m.actionSteps?.length" class="section">
                          <h5>维权步骤</h5>
                          <ol>
                            <li v-for="(s, i) in m.actionSteps" :key="`step-${i}`">{{ s }}</li>
                          </ol>
                        </div>
                        <div v-if="m.evidenceChecklist?.length" class="section">
                          <h5>证据清单</h5>
                          <ul>
                            <li v-for="(s, i) in m.evidenceChecklist" :key="`ev-${i}`">{{ s }}</li>
                          </ul>
                        </div>
                        <div v-if="m.riskWarnings?.length" class="section">
                          <h5>风险与时效</h5>
                          <ul>
                            <li v-for="(s, i) in m.riskWarnings" :key="`risk-${i}`">{{ s }}</li>
                          </ul>
                        </div>
                        <div v-if="m.followupQuestions?.length" class="section">
                          <h5>建议补充信息</h5>
                          <ul>
                            <li v-for="(s, i) in m.followupQuestions" :key="`q-${i}`">{{ s }}</li>
                          </ul>
                        </div>
                      </div>

                      <div
                        v-if="m.references?.length"
                        class="refs-card"
                      >
                        <div class="refs-cap">
                          <span class="material-symbols-outlined">menu_book</span>
                          <span>关联材料</span>
                        </div>
                        <a
                          v-for="(r, idx) in m.references"
                          :key="idx"
                          href="#"
                          class="refs-link"
                          @click.prevent
                        >
                          {{ r }}
                        </a>
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            </template>
          </div>
        </div>

        <!-- Input Area (Fixed Bottom)：回形针在发送旁；拖放落在输入框或已选文件区域 -->
        <div class="composer">
          <div class="composer-drop" @drop="onDrop" @dragover="onDragOver">
            <div v-if="filesReading || uploadingFiles.length" class="filechips-wrap">
              <div v-if="filesReading" class="file-reading" role="status" aria-live="polite">
                <span class="file-spinner" aria-hidden="true"></span>
                <span class="file-reading-text">正在读取附件内容（图片将识别为文字，文档将提取正文）…</span>
              </div>
              <div v-if="uploadingFiles.length" class="filechips">
                <div
                  v-for="f in uploadingFiles"
                  :key="f.id"
                  class="chip"
                  :title="
                    f.extractError ||
                    (f.extractedText ? String(f.extractedText).slice(0, 500) : f.name)
                  "
                >
                  <span class="chip-name">{{ f.name }}</span>
                  <span v-if="f.extractError" class="chip-badge err">失败</span>
                  <span v-else-if="f.extractedText" class="chip-badge ok">已识别</span>
                  <button
                    class="chip-x"
                    type="button"
                    title="移除"
                    @click="uploadingFiles = uploadingFiles.filter((x) => x.id !== f.id)"
                  >
                    ×
                  </button>
                </div>
              </div>
            </div>

            <div
              class="inputbox"
              title="点击回形针上传附件，或将文件拖入此区域（支持常见图片与文档格式）"
            >
              <textarea
                v-model="input"
                class="textarea custom-scrollbar"
                placeholder="请描述您的法律问题；支持上传附件或将文件拖入此区域…"
                rows="1"
                @keydown="onComposerKeydown"
                @paste="onComposerPaste"
              ></textarea>
              <div class="input-actions">
                <button class="attach-btn" type="button" @click="pickFiles">
                  <span class="material-symbols-outlined">attach_file</span>
                </button>
                <button class="send-btn" type="button" :disabled="chat.loading" @click="send">
                  <span class="material-symbols-outlined">send</span>
                </button>
              </div>
            </div>
          </div>

          <p class="composer-tip">
            Enter 发送，Shift+Enter 换行。AI 咨询提供初步分析建议，不构成正式法律意见或代理关系。
          </p>
          <input ref="fileInputRef" class="hidden" type="file" multiple :accept="acceptTypes" @change="onFileChange" />
        </div>
      </section>
    </main>
  </AppLayout>

  <Teleport to="body">
    <div
      v-if="attPreview"
      class="att-preview-overlay"
      role="dialog"
      aria-modal="true"
      :aria-label="attPreview.title"
      @click.self="closeAttPreview"
    >
      <div
        class="att-preview-panel"
        :class="{ 'att-preview-panel--pdf': attPreview.kind === 'pdf' }"
        @click.stop
      >
        <header class="att-preview-bar">
          <span class="att-preview-title">{{ attPreview.title }}</span>
          <button type="button" class="att-preview-close" title="关闭 (Esc)" @click="closeAttPreview">
            <span class="material-symbols-outlined">close</span>
          </button>
        </header>
        <div class="att-preview-stage">
          <img
            v-if="attPreview.kind === 'image'"
            class="att-preview-img"
            :alt="attPreview.title"
            :src="attPreview.href"
          />
          <iframe
            v-else-if="attPreview.kind === 'pdf'"
            class="att-preview-frame"
            :title="attPreview.title"
            :src="attPreview.href"
          />
          <div v-else class="att-preview-other">
            <p class="att-preview-other-tip">该类型请在浏览器中打开查看。</p>
            <a
              class="att-preview-other-link"
              :href="attPreview.href"
              target="_blank"
              rel="noopener noreferrer"
            >
              <span class="material-symbols-outlined">open_in_new</span>
              在新标签页打开或下载
            </a>
          </div>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.main {
  flex: 1;
  display: flex;
  overflow: hidden;
  background: var(--background);
  min-height: 0;
}
.history {
  width: 320px;
  flex: 0 0 auto;
  background: var(--surface-container-lowest);
  border-right: 1px solid var(--outline-variant);
  display: flex;
  flex-direction: column;
}
.mobile-history-btn {
  display: none;
  border: none;
  background: transparent;
  color: var(--on-secondary-container);
  cursor: pointer;
  width: 28px;
  height: 28px;
  border-radius: 6px;
}
.mobile-history-btn:hover {
  background: rgba(255, 255, 255, 0.25);
}
.history-top {
  padding: 16px;
  border-bottom: 1px solid var(--outline-variant);
}
.ghost-btn {
  width: 100%;
  height: 42px;
  background: transparent;
  border: 1px solid var(--primary);
  color: var(--primary);
  border-radius: var(--radius-lg);
  font-weight: 500;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  cursor: pointer;
  transition: background 0.2s;
}
.ghost-btn:hover {
  background: var(--surface-variant);
}
.history-list {
  flex: 1;
  overflow: auto;
  padding: 8px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.history-cap {
  margin: 8px 8px 4px;
  font-size: 12px;
  font-weight: 600;
  color: var(--outline);
  letter-spacing: 0.05em;
}
.history-item {
  position: relative;
  border-radius: var(--radius-lg);
  padding: 12px;
  cursor: pointer;
  border-left: 4px solid transparent;
  transition: background 0.2s;
}
.history-item:hover {
  background: var(--surface-variant);
}
.history-item.active {
  background: var(--surface-variant);
  box-shadow: var(--shadow-sm);
  border-left-color: var(--primary);
}
.history-meta {
  padding-right: 24px;
}
.history-title {
  margin: 0;
  font-size: 14px;
  font-weight: 500;
  color: var(--on-surface);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.history-time {
  margin: 4px 0 0 0;
  font-size: 12px;
  font-weight: 600;
  color: var(--secondary);
}
.history-del {
  position: absolute;
  right: 8px;
  top: 50%;
  transform: translateY(-50%);
  border: none;
  background: transparent;
  color: var(--outline);
  opacity: 0;
  cursor: pointer;
  transition: opacity 0.2s, color 0.2s;
}
.history-item:hover .history-del {
  opacity: 1;
}
.history-del:hover {
  color: var(--error);
}
.chat {
  flex: 1;
  display: flex;
  flex-direction: column;
  background: var(--surface-bright);
  overflow: hidden;
  min-height: 0;
}
.chat-head {
  height: 60px;
  flex: 0 0 auto;
  background: var(--secondary-container);
  color: var(--on-secondary-container);
  border-bottom: 1px solid var(--outline-variant);
  box-shadow: var(--shadow-sm);
  display: flex;
  align-items: center;
  justify-content: flex-start;
  padding: 0 24px;
  z-index: 10;
}
.chat-head-left {
  display: flex;
  align-items: center;
  gap: 16px;
}
.chat-head-title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
}
.stream {
  flex: 1;
  overflow: auto;
  padding: 24px;
  min-height: 0;
}
.stream-inner {
  max-width: 56rem;
  margin: 0 auto;
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 32px;
}
.stream--welcome {
  display: flex;
  flex-direction: column;
}
.stream--welcome .stream-inner {
  flex: 1;
  justify-content: center;
  align-items: center;
  min-height: 0;
}
.empty-note {
  background: var(--surface-container-lowest);
  border: 1px solid var(--outline-variant);
  border-radius: 16px;
  box-shadow: var(--shadow-sm);
  padding: 16px;
}
.empty-title {
  font-weight: 700;
  color: var(--on-surface);
}
.empty-sub {
  margin-top: 6px;
  color: var(--on-surface-variant);
  font-size: 14px;
}
.row {
  width: 100%;
  display: flex;
}
.row-user {
  justify-content: flex-end;
}
.row-ai {
  justify-content: flex-start;
}
.bubble-user {
  background: var(--surface-container-lowest);
  border: 1px solid var(--outline-variant);
  box-shadow: var(--shadow-sm);
  border-radius: 16px;
  border-top-right-radius: 4px;
  padding: 16px;
  max-width: 80%;
}
.user-attachments {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-bottom: 12px;
}
.user-att-card {
  border: 1px solid var(--outline-variant);
  border-radius: 12px;
  padding: 0;
  background: var(--surface-variant);
  overflow: hidden;
}
.user-att-chip {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 10px 12px;
  border: none;
  background: transparent;
  cursor: pointer;
  text-align: left;
  font: inherit;
  color: var(--on-surface);
  transition: background 0.15s;
}
.user-att-chip:hover {
  background: rgba(0, 0, 0, 0.04);
}
.user-att-chip-icon {
  flex: 0 0 auto;
  font-size: 22px;
  color: var(--primary);
}
.user-att-chip-name {
  flex: 1;
  min-width: 0;
  font-size: 14px;
  font-weight: 600;
  word-break: break-all;
  line-height: 1.35;
}
.user-att-chip-open {
  flex: 0 0 auto;
  font-size: 20px;
  color: var(--outline);
}
.user-att-chip-hint {
  flex: 0 0 auto;
  font-size: 12px;
  font-weight: 600;
  color: var(--secondary);
}

/* Teleport：全屏附件预览 */
.att-preview-overlay {
  position: fixed;
  inset: 0;
  z-index: 10000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: max(12px, env(safe-area-inset-top)) max(12px, env(safe-area-inset-right))
    max(12px, env(safe-area-inset-bottom)) max(12px, env(safe-area-inset-left));
  box-sizing: border-box;
  background: rgba(0, 0, 0, 0.88);
}
.att-preview-panel {
  display: flex;
  flex-direction: column;
  align-items: center;
  max-width: 100%;
  max-height: 100%;
  min-width: 0;
  min-height: 0;
}
.att-preview-panel--pdf {
  width: min(96vw, 1680px);
  height: min(94vh, 1200px);
  align-self: center;
}
.att-preview-bar {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  max-width: min(96vw, 1680px);
  padding: 4px 4px 14px;
  box-sizing: border-box;
}
.att-preview-title {
  flex: 1;
  min-width: 0;
  font-size: 15px;
  font-weight: 600;
  color: #f0f0f0;
  word-break: break-all;
}
.att-preview-close {
  flex: 0 0 auto;
  width: 44px;
  height: 44px;
  border: none;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.14);
  color: #fff;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
}
.att-preview-close:hover {
  background: rgba(255, 255, 255, 0.26);
}
.att-preview-stage {
  flex: 1;
  min-height: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
}
.att-preview-panel:not(.att-preview-panel--pdf) .att-preview-stage {
  flex: 0 1 auto;
}
.att-preview-img {
  display: block;
  max-width: min(96vw, 1680px);
  max-height: calc(100vh - 96px);
  width: auto;
  height: auto;
  object-fit: contain;
  border-radius: 8px;
  box-shadow: 0 12px 48px rgba(0, 0, 0, 0.5);
}
.att-preview-panel--pdf .att-preview-stage {
  flex: 1;
  min-height: 0;
}
.att-preview-frame {
  width: 100%;
  height: 100%;
  min-height: 0;
  border: none;
  border-radius: 8px;
  background: #1e1e1e;
}
.att-preview-other {
  text-align: center;
  padding: 32px 20px;
  color: #e8e8e8;
}
.att-preview-other-tip {
  margin: 0;
  font-size: 14px;
}
.att-preview-other-link {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin-top: 16px;
  font-size: 15px;
  font-weight: 600;
  color: #a8c7ff;
  text-decoration: none;
}
.att-preview-other-link:hover {
  text-decoration: underline;
}
.text-md {
  margin: 0;
  font-size: 16px;
  line-height: 1.6;
  color: var(--on-surface);
}
.ai-wrap {
  width: 100%;
  max-width: 90%;
  display: flex;
  gap: 16px;
}
.ai-avatar {
  width: 40px;
  height: 40px;
  border-radius: 999px;
  background: var(--tertiary);
  color: var(--on-tertiary);
  display: flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
  box-shadow: var(--shadow-sm);
}
.bubble-ai {
  background: var(--tertiary-fixed);
  color: var(--on-tertiary-fixed);
  border-radius: 16px;
  border-top-left-radius: 4px;
  padding: 16px;
  box-shadow: var(--shadow-sm);
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.ai-h4 {
  margin: 0;
  font-size: 24px;
  font-weight: 600;
  line-height: 1.4;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--primary-fixed-dim);
}
.streaming-tip {
  display: inline-flex;
  align-items: center;
  height: 24px;
  padding: 0 10px;
  border-radius: 999px;
  background: var(--surface-container-lowest);
  color: var(--primary);
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.04em;
}
.welcome {
  width: 100%;
  max-width: 56rem;
  box-sizing: border-box;
  background: var(--surface-container-lowest);
  border: 1px solid var(--outline-variant);
  border-radius: 16px;
  box-shadow: var(--shadow-sm);
  padding: 18px;
  text-align: center;
}
.welcome-title {
  font-size: 18px;
  font-weight: 700;
  color: var(--on-surface);
}
.welcome-sub {
  margin-top: 6px;
  color: var(--on-surface-variant);
  font-size: 14px;
  line-height: 1.7;
}
.ai-h5 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
}
.ai-p {
  margin: 0;
  font-size: 16px;
  line-height: 1.6;
}
.ai-ul,
.ai-ol {
  margin: 0;
  padding-left: 24px;
  font-size: 14px;
  line-height: 1.5;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.refs-card {
  background: var(--surface-container-lowest);
  border: 1px solid var(--outline-variant);
  border-left: 4px solid var(--primary);
  border-radius: var(--radius-lg);
  padding: 8px;
  box-shadow: var(--shadow-sm);
}
.refs-cap {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
  color: var(--secondary);
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.06em;
  text-transform: uppercase;
}
.refs-link {
  display: block;
  color: var(--primary);
  font-size: 14px;
  text-decoration: none;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.refs-link:hover {
  text-decoration: underline;
}
.structured {
  border: 1px solid var(--outline-variant);
  border-radius: 10px;
  background: var(--surface-container-lowest);
  padding: 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.section h5 {
  margin: 0 0 6px;
  color: var(--primary);
  font-size: 13px;
  letter-spacing: 0.04em;
  text-transform: uppercase;
}
.section p {
  margin: 0;
  font-size: 14px;
  line-height: 1.6;
}
.structured-plain,
.structured-li {
  white-space: pre-wrap;
  word-break: break-word;
}
.section ul,
.section ol {
  margin: 0;
  padding-left: 18px;
  font-size: 14px;
  line-height: 1.6;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.composer {
  padding: 24px;
  background: var(--surface-container-lowest);
  border-top: 1px solid var(--outline-variant);
  flex: 0 0 auto;
}
.composer-drop {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.filechips-wrap {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.file-reading {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  border-radius: 10px;
  background: var(--surface-bright);
  border: 1px solid var(--outline-variant);
  color: var(--secondary);
  font-size: 13px;
}
.file-spinner {
  width: 20px;
  height: 20px;
  border: 2px solid var(--outline-variant);
  border-top-color: var(--primary);
  border-radius: 50%;
  flex-shrink: 0;
  animation: file-spin 0.65s linear infinite;
}
.file-reading-text {
  line-height: 1.4;
}
@keyframes file-spin {
  to {
    transform: rotate(360deg);
  }
}
.inputbox {
  border: 1px solid var(--outline);
  border-radius: 12px;
  background: var(--surface-bright);
  box-shadow: var(--shadow-sm);
  display: flex;
  align-items: stretch;
  overflow: hidden;
}
.inputbox:focus-within {
  border-color: var(--primary);
  box-shadow: 0 0 0 1px var(--primary);
}
.input-actions {
  flex-shrink: 0;
  display: flex;
  flex-direction: row;
  align-items: flex-end;
  gap: 4px;
  align-self: stretch;
  padding: 4px 6px 6px 2px;
  box-sizing: border-box;
}
.attach-btn {
  width: 36px;
  height: 36px;
  border: none;
  background: transparent;
  color: var(--secondary);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: color 0.2s;
  flex-shrink: 0;
}
.attach-btn:hover {
  color: var(--primary);
}
.textarea {
  flex: 1;
  min-width: 0;
  background: transparent;
  border: none;
  outline: none;
  resize: none;
  padding: 10px 8px 10px 12px;
  font-size: 16px;
  line-height: 1.6;
  color: var(--on-surface);
  min-height: 52px;
  max-height: 150px;
}
.send-btn {
  width: 36px;
  height: 36px;
  border: none;
  border-radius: 8px;
  background: var(--primary);
  color: var(--on-primary);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  box-shadow: var(--shadow-sm);
  transition: box-shadow 0.2s, opacity 0.2s;
}
.send-btn:hover {
  box-shadow: var(--shadow-md);
}
.send-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
.composer-tip {
  margin: 4px 0 0;
  text-align: center;
  font-size: 12px;
  color: var(--outline);
  letter-spacing: 0.05em;
}
.hidden {
  display: none;
}
.filechips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.chip {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  background: var(--surface-container-low);
  border: 1px solid var(--outline-variant);
  border-radius: 999px;
  padding: 6px 10px;
  font-size: 12px;
  color: var(--on-surface);
  max-width: 100%;
}
.chip-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: min(220px, 55vw);
}
.chip-badge {
  flex-shrink: 0;
  font-size: 10px;
  font-weight: 700;
  padding: 2px 6px;
  border-radius: 6px;
  letter-spacing: 0.02em;
}
.chip-badge.ok {
  background: rgba(15, 159, 110, 0.15);
  color: #0f9f6e;
}
.chip-badge.err {
  background: var(--error-container);
  color: var(--on-error-container);
}
.chip-x {
  border: none;
  background: transparent;
  cursor: pointer;
  font-size: 16px;
  line-height: 1;
  color: var(--outline);
}
.chip-x:hover {
  color: var(--error);
}

@media (max-width: 900px) {
  .history {
    position: fixed;
    left: 0;
    top: 64px;
    bottom: 0;
    z-index: 60;
    width: 86vw;
    max-width: 340px;
    transform: translateX(-100%);
    transition: transform 0.2s ease;
  }
  .history.show {
    transform: translateX(0);
  }
  .mobile-history-btn {
    display: inline-flex;
    align-items: center;
    justify-content: center;
  }
}
</style>
