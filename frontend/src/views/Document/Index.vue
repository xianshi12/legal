<script setup>
import AppLayout from '../../components/common/AppLayout.vue'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { exportDocumentFile, generateDocument, getDocumentTemplates, optimizeDocument } from '../../api/document'
import { tryExportPdfInBrowser, triggerBlobDownload } from '../../utils/exportPdfBrowser'

const router = useRouter()
const loading = ref(false)
const error = ref('')
const result = ref(null)
/** 最近一次「标准生成」成功返回的完整结果；切到优化/风险时预览清空，切回生成时恢复 */
const lastGenerateResult = ref(null)
const templates = ref([])
const selectedFile = ref(null)
const fileInputRef = ref(null)
const uploadDragOver = ref(false)
const exportBusy = ref(false)

const form = reactive({
  documentType: 'iou',
  category: '基础文书',
  scenario: '民间借贷',
  outputMode: 'generate',
  fields: {},
  facts: '',
  extraRequirements: '',
  rawText: '',
})

const currentTemplate = computed(() => templates.value.find((item) => item.type === form.documentType))
const groupedTemplates = computed(() => {
  const groups = {}
  for (const item of templates.value) {
    groups[item.category] ||= []
    groups[item.category].push(item)
  }
  return groups
})
const modeLabel = computed(() => {
  const map = { generate: '标准生成', optimize: '文书优化', risk: '风险标注' }
  return map[form.outputMode] || '标准生成'
})
const canUpload = computed(() => currentTemplate.value?.uploadSupported)
const selectedFileName = computed(() => selectedFile.value?.name || '')

const outputModes = [
  { value: 'generate', label: '标准生成', desc: '按模板与事实生成完整文书', icon: 'edit_document' },
  { value: 'optimize', label: '文书优化', desc: '润色表述、补充结构', icon: 'auto_fix_high' },
  { value: 'risk', label: '风险标注', desc: '识别条款风险并给出提示', icon: 'warning' },
]

watch(currentTemplate, (tpl) => {
  if (!tpl) return
  form.category = tpl.category
  form.scenario = tpl.scenario
  const next = {}
  for (const field of tpl.fields) next[field] = form.fields[field] || ''
  form.fields = next
}, { immediate: true })

function snapshotGenerateResult(data) {
  if (data == null) return null
  try {
    return structuredClone(data)
  } catch {
    try {
      return JSON.parse(JSON.stringify(data))
    } catch {
      return data
    }
  }
}

watch(
  () => form.outputMode,
  (mode, prev) => {
    if (mode === 'generate') {
      selectedFile.value = null
      if (fileInputRef.value) fileInputRef.value.value = ''
    }
    if (prev === undefined || mode === prev) return
    error.value = ''

    if (prev === 'generate' && mode !== 'generate') {
      /* 离开标准生成：预览清空，与优化/风险隔离 */
      result.value = null
      return
    }
    if (mode === 'generate' && prev !== 'generate') {
      /* 回到标准生成：恢复上次生成预览（直至刷新页面或点清空） */
      result.value = lastGenerateResult.value ? snapshotGenerateResult(lastGenerateResult.value) : null
      return
    }
    /* 优化 ↔ 风险 */
    result.value = null
  },
)

async function loadTemplates() {
  try {
    templates.value = await getDocumentTemplates()
    if (templates.value.length && !templates.value.some((item) => item.type === form.documentType)) {
      form.documentType = templates.value[0].type
    }
  } catch (e) {
    error.value = e.message
  }
}

async function submit() {
  loading.value = true
  error.value = ''
  try {
    if (form.outputMode === 'generate') {
      result.value = await generateDocument({
        documentType: form.documentType,
        category: form.category,
        scenario: form.scenario,
        outputMode: form.outputMode,
        fields: form.fields,
        facts: form.facts,
        extraRequirements: form.extraRequirements,
      })
      lastGenerateResult.value = snapshotGenerateResult(result.value)
    } else {
      result.value = await optimizeDocument({
        documentType: form.documentType,
        outputMode: form.outputMode,
        facts: form.facts,
        extraRequirements: form.extraRequirements,
        rawText: form.rawText,
        file: selectedFile.value,
      })
    }
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

function resetForm() {
  form.outputMode = 'generate'
  form.facts = ''
  form.extraRequirements = ''
  form.rawText = ''
  selectedFile.value = null
  if (fileInputRef.value) fileInputRef.value.value = ''
  if (currentTemplate.value) {
    form.fields = Object.fromEntries(currentTemplate.value.fields.map((field) => [field, '']))
  }
  result.value = null
  lastGenerateResult.value = null
  error.value = ''
}

function setFile(file) {
  if (!file) return
  selectedFile.value = file
}

function onFileChange(event) {
  setFile(event.target.files?.[0] || null)
}

function openFilePicker() {
  fileInputRef.value?.click()
}

function onUploadCardDragOver(e) {
  e.preventDefault()
  if (form.outputMode === 'generate') return
  uploadDragOver.value = true
}

function onUploadDragLeave() {
  uploadDragOver.value = false
}

function onUploadDrop(e) {
  e.preventDefault()
  uploadDragOver.value = false
  if (form.outputMode === 'generate') return
  const f = e.dataTransfer?.files?.[0]
  if (f) setFile(f)
}

function clearFile() {
  selectedFile.value = null
  if (fileInputRef.value) fileInputRef.value.value = ''
}

async function copyResult() {
  const text = result.value?.content
  if (!text) {
    ElMessage.warning('暂无正文可复制')
    return
  }
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('正文已复制到剪贴板')
  } catch {
    const ta = document.createElement('textarea')
    ta.value = text
    ta.setAttribute('readonly', 'readonly')
    document.body.appendChild(ta)
    ta.select()
    try {
      document.execCommand('copy')
      ElMessage.success('已复制（兼容模式）')
    } catch {
      ElMessage.error('复制失败，请手动选中预览区文字复制')
    } finally {
      document.body.removeChild(ta)
    }
  }
}

async function exportAs(format) {
  const text = result.value?.content
  if (!text) {
    ElMessage.warning('请先生成或优化文书后再导出')
    return
  }
  exportBusy.value = true
  try {
    if (format === 'PDF') {
      const local = await tryExportPdfInBrowser(
        result.value.title || currentTemplate.value?.name || '法律文书',
        text,
      )
      if (local.ok) {
        triggerBlobDownload(local.blob, local.filename)
        ElMessage.success(`已下载 ${local.filename}（浏览器生成）`)
        return
      }
      ElMessage.info('未检测到 public/fonts 下的中文字体，已改用服务端生成 PDF')
    }
    const { blob, filename } = await exportDocumentFile({
      format,
      title: result.value.title || currentTemplate.value?.name || '法律文书',
      content: text,
    })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = filename
    a.click()
    URL.revokeObjectURL(url)
    ElMessage.success(`已下载 ${filename}`)
  } catch (e) {
    ElMessage.error(e?.message || '导出失败')
  } finally {
    exportBusy.value = false
  }
}

onMounted(async () => {
  await loadTemplates()
})
</script>

<template>
  <AppLayout>
    <div class="page custom-scrollbar">
      <div class="wrap">
        <div class="crumbs" aria-label="面包屑">
          <a class="link" href="#" @click.prevent="router.push('/dashboard')">主页</a>
          <span class="material-symbols-outlined chev">chevron_right</span>
          <span class="here">文书生成</span>
        </div>

        <div class="hero">
          <div class="heroInner">
            <h1 class="h1">法律文书生成</h1>
            <p class="sub">
              上方卡片选择模板、输出模式与附件；左侧主区填写事实与结构化字段，右侧实时查看生成结果。
            </p>
          </div>
        </div>

        <section class="topCards" aria-label="模板、模式与上传">
          <!-- 文书模板 -->
          <article class="card card--accent-primary">
            <div class="card-head">
              <div class="icon-wrap icon-wrap--primary">
                <span class="material-symbols-outlined">description</span>
              </div>
              <span class="pill">模板</span>
            </div>
            <h2 class="card-title">文书模板</h2>
            <p class="card-desc">按分类选择具体模板，系统据此匹配字段结构与默认案情要素。</p>
            <label class="field-label">文书模板</label>
            <select v-model="form.documentType" class="control" aria-label="文书类型">
              <optgroup v-for="(items, group) in groupedTemplates" :key="group" :label="group">
                <option v-for="item in items" :key="item.type" :value="item.type">{{ item.name }}</option>
              </optgroup>
            </select>
          </article>

          <!-- 输出模式 -->
          <article class="card card--accent-tertiary">
            <div class="card-head">
              <div class="icon-wrap icon-wrap--tertiary">
                <span class="material-symbols-outlined">tune</span>
              </div>
              <span class="pill">模式</span>
            </div>
            <h2 class="card-title">输出模式</h2>
            <p class="card-desc">标准生成从零撰写；优化与风险模式可结合上传或粘贴正文。</p>
            <div class="mode-list" role="radiogroup" aria-label="输出模式">
              <button
                v-for="m in outputModes"
                :key="m.value"
                type="button"
                class="mode-item"
                :class="{ 'mode-item--active': form.outputMode === m.value }"
                role="radio"
                :aria-checked="form.outputMode === m.value"
                @click="form.outputMode = m.value"
              >
                <span class="material-symbols-outlined mode-icon">{{ m.icon }}</span>
                <span class="mode-text">
                  <span class="mode-label">{{ m.label }}</span>
                  <span class="mode-sub">{{ m.desc }}</span>
                </span>
                <span
                  class="material-symbols-outlined mode-check"
                  :class="{ visible: form.outputMode === m.value }"
                >check_circle</span>
            </button>
          </div>
          </article>

          <!-- 文件上传 -->
          <article
            class="card card--accent-warn upload-card"
            :class="{ 'upload-card--drag': uploadDragOver, 'upload-card--disabled': form.outputMode === 'generate' }"
            @dragover="onUploadCardDragOver"
            @dragleave="onUploadDragLeave"
            @drop="onUploadDrop"
          >
            <div class="card-head">
              <div class="icon-wrap icon-wrap--warn">
                <span class="material-symbols-outlined">cloud_upload</span>
              </div>
              <span class="pill">附件</span>
              </div>
            <h2 class="card-title">文件上传</h2>
            <p v-if="form.outputMode === 'generate'" class="card-desc muted-strong">
              标准生成无需上传。切换到「文书优化」或「风险标注」后可拖拽或选择文件。
            </p>
            <p v-else class="card-desc">
              {{ canUpload ? '支持 doc、docx、pdf、txt 等（也可在下方粘贴正文）。' : '该模板建议粘贴正文；仍可尝试上传文本类文件。' }}
            </p>
            <input
              ref="fileInputRef"
              type="file"
              class="sr-only"
              :disabled="form.outputMode === 'generate'"
              @change="onFileChange"
            />
            <div
              class="drop-zone"
              :class="{ 'drop-zone--active': uploadDragOver && form.outputMode !== 'generate' }"
              role="button"
              :tabindex="form.outputMode === 'generate' ? -1 : 0"
              @click="form.outputMode !== 'generate' && openFilePicker()"
              @keydown.enter.prevent="form.outputMode !== 'generate' && openFilePicker()"
              @keydown.space.prevent="form.outputMode !== 'generate' && openFilePicker()"
            >
              <span class="material-symbols-outlined drop-icon">upload_file</span>
              <p v-if="!selectedFileName" class="drop-hint">拖拽文件到此处，或点击选择</p>
              <p v-else class="drop-file">{{ selectedFileName }}</p>
              </div>
            <div v-if="selectedFileName && form.outputMode !== 'generate'" class="file-actions">
              <button type="button" class="text-btn" @click.stop="openFilePicker">重新选择</button>
              <button type="button" class="text-btn text-btn--danger" @click.stop="clearFile">移除</button>
                </div>
          </article>
        </section>

        <div class="main-grid">
          <!-- 数据录入 -->
          <section class="panel panel-form form-panel">
            <div class="panel-head">
              <div>
                <h2 class="panel-title">数据录入</h2>
                <p class="panel-sub">当前模板：{{ currentTemplate?.name || '—' }}（{{ currentTemplate?.category || '—' }}）</p>
              </div>
              <button type="button" class="icon-only" title="重置" aria-label="重置表单" @click="resetForm">
                <span class="material-symbols-outlined">restart_alt</span>
              </button>
            </div>

            <div v-if="form.outputMode === 'generate'" class="block">
              <h3 class="section-heading">
                <span class="material-symbols-outlined sm">person</span>
                模板字段
              </h3>
              <div class="field-grid">
                <label v-for="field in currentTemplate?.fields || []" :key="field" class="stack">
                  <span class="mini-label">{{ field }}</span>
                  <input v-model.trim="form.fields[field]" class="control" :placeholder="`填写${field}`" />
                </label>
              </div>
            </div>

            <div class="block">
              <h3 class="section-heading">
                <span class="material-symbols-outlined sm">notes</span>
                事实描述
              </h3>
              <textarea
                v-model.trim="form.facts"
                class="control textarea"
                rows="5"
                placeholder="按时间线说明事实、金额、期限、履行情况、沟通记录和你希望达到的结果。"
              />
                </div>

            <div class="block">
              <h3 class="section-heading">
                <span class="material-symbols-outlined sm">rule</span>
                额外要求
              </h3>
              <textarea
                v-model.trim="form.extraRequirements"
                class="control textarea"
                rows="3"
                placeholder="例如：语气正式、增加违约责任、强调证据目录、适合向法院提交。"
              />
                </div>

            <div v-if="form.outputMode !== 'generate'" class="block">
              <h3 class="section-heading">
                <span class="material-symbols-outlined sm">content_paste</span>
                已有文书正文（可选）
              </h3>
              <textarea
                v-model.trim="form.rawText"
                class="control textarea"
                rows="6"
                placeholder="粘贴已有文书正文，可与上传文件二选一或同时使用，由服务端综合处理。"
              />
                </div>

                <div v-if="error" class="notice error">{{ error }}</div>

            <div class="form-actions">
                  <button class="btn ghost" type="button" @click="resetForm">清空</button>
                  <button class="btn primary" type="button" :disabled="loading" @click="submit">
                {{ loading ? '处理中…' : '生成 / 分析' }}
                  </button>
            </div>
          </section>

          <!-- 结果预览 -->
          <section class="panel panel-preview">
            <div class="preview-shell">
              <div class="preview-toolbar">
                <div class="preview-toolbar-left">
                  <span class="material-symbols-outlined preview-toolbar-icon">visibility</span>
                  <h3 class="preview-toolbar-title">结果预览</h3>
                </div>
                <div class="preview-meta">
                  <span class="status-pill">{{ result?.status || '待生成' }}</span>
                </div>
              </div>

              <div class="preview-body custom-scrollbar">
                <div class="paper">
                  <h2 class="paper-title">{{ result?.title || currentTemplate?.name || '预览' }}</h2>
                  <div v-if="!result?.content?.trim()" class="paper-content doc-plain doc-plain--placeholder">
                    生成后的文书正文会显示在这里。
                  </div>
                  <div v-else class="paper-content doc-plain">{{ result.content }}</div>
                </div>

                <div class="sub-card">
                  <h4>
                    <span class="material-symbols-outlined sm">auto_fix_high</span>
                    优化建议
                  </h4>
                  <p v-if="!result?.optimizationSuggestions?.length">生成后会列出可补强条款和表达优化建议。</p>
                  <ul v-else class="plain-bullet-list">
                    <li v-for="(item, idx) in result.optimizationSuggestions" :key="'s-' + idx" class="plain-line">
                      {{ item }}
                    </li>
                  </ul>
                </div>

                <div class="foot-grid">
                  <div>
                    <h4><span class="material-symbols-outlined sm">report</span> 风险标注</h4>
                    <div v-if="!result?.riskAnnotations?.length" class="tags-empty">暂无风险标注</div>
                    <div v-else class="tags-md">
                      <div v-for="(item, idx) in result.riskAnnotations" :key="'r-' + idx" class="plain-chip">
                        {{ item }}
                      </div>
                    </div>
                  </div>
                  <div>
                    <h4><span class="material-symbols-outlined sm">folder_open</span> 证据清单</h4>
                    <div v-if="!result?.evidenceChecklist?.length" class="tags-empty">暂无证据条目</div>
                    <div v-else class="tags-md">
                      <div v-for="(item, idx) in result.evidenceChecklist" :key="'e-' + idx" class="plain-chip">
                        {{ item }}
                      </div>
                    </div>
                  </div>
                </div>
              </div>

              <div class="preview-footer">
                <span class="footer-hint">{{ currentTemplate?.name }} · {{ modeLabel }}</span>
                <div class="footer-actions">
                  <button
                    type="button"
                    class="btn ghost"
                    :disabled="exportBusy"
                    title="复制当前正文到剪贴板"
                    @click="copyResult"
                  >
                    <span class="material-symbols-outlined btn-ic">content_copy</span>
                    复制正文
                  </button>
                  <el-dropdown trigger="click" :disabled="exportBusy" @command="exportAs">
                    <button type="button" class="btn primary export-dropdown-trigger" :disabled="exportBusy">
                      <span class="material-symbols-outlined btn-ic">download</span>
                      导出
                      <span class="material-symbols-outlined btn-ic export-chev">expand_more</span>
                    </button>
                    <template #dropdown>
                      <el-dropdown-menu>
                        <el-dropdown-item command="TXT">TXT 纯文本</el-dropdown-item>
                        <el-dropdown-item command="DOCX">DOCX 文档</el-dropdown-item>
                        <el-dropdown-item command="PDF">PDF 文档</el-dropdown-item>
                      </el-dropdown-menu>
                    </template>
                  </el-dropdown>
                </div>
              </div>
            </div>
          </section>
        </div>
      </div>
    </div>
  </AppLayout>
</template>

<style scoped>
.page {
  height: 100%;
  overflow: auto;
  background: var(--background);
}
.wrap {
  max-width: 1280px;
  margin: 0 auto;
  padding: 24px 24px 40px;
}
.crumbs {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 16px;
  font-size: 14px;
  color: var(--on-surface-variant);
}
.link {
  color: var(--on-surface-variant);
  text-decoration: none;
}
.link:hover {
  color: var(--primary);
}
.chev {
  font-size: 14px;
  color: var(--outline);
}
.here {
  color: var(--on-surface);
  font-weight: 500;
}

.hero {
  position: relative;
  overflow: hidden;
  border-radius: 12px;
  border: 1px solid var(--surface-variant);
  background: linear-gradient(135deg, var(--surface) 0%, var(--secondary-container) 100%);
  padding: 32px;
  margin-bottom: 24px;
  box-shadow: var(--shadow-sm);
}
.heroInner {
  text-align: center;
  max-width: 820px;
  margin: 0 auto;
}
.h1 {
  margin: 0 0 8px;
  font-size: 40px;
  font-weight: 700;
  letter-spacing: -0.02em;
  line-height: 1.2;
  color: var(--on-surface);
}
.sub {
  margin: 0;
  font-size: 18px;
  line-height: 1.7;
  color: var(--on-surface-variant);
}
.topCards {
  display: grid;
  grid-template-columns: 1fr;
  gap: 24px;
  margin-bottom: 28px;
}
@media (min-width: 900px) {
  .topCards {
    grid-template-columns: repeat(3, 1fr);
  }
}

.card {
  position: relative;
  overflow: hidden;
  background: var(--surface-container-lowest);
  border: 1px solid var(--surface-container-highest);
  border-radius: var(--radius-lg);
  padding: 24px;
  box-shadow: var(--shadow-sm);
  transition: box-shadow 0.2s ease;
}
.card:hover {
  box-shadow: var(--shadow-md);
}
.card::before {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 4px;
}
.card--accent-primary::before {
  background: var(--primary-container);
}
.card--accent-tertiary::before {
  background: var(--tertiary);
}
.card--accent-warn::before {
  background: var(--on-error-container);
}

.card-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 16px;
}
.icon-wrap {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 48px;
  height: 48px;
  border-radius: var(--radius-lg);
}
.icon-wrap--primary {
  background: color-mix(in srgb, var(--secondary-container) 50%, transparent);
  color: var(--primary-container);
}
.icon-wrap--tertiary {
  background: var(--tertiary-fixed);
  color: var(--tertiary);
}
.icon-wrap--warn {
  background: var(--error-container);
  color: var(--on-error-container);
}
.pill {
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  padding: 4px 8px;
  border-radius: 6px;
  background: var(--surface-container-low);
  color: var(--on-surface-variant);
}
.card-title {
  margin: 0 0 8px;
  font-size: 22px;
  font-weight: 600;
  color: var(--on-surface);
}
.card-desc {
  margin: 0 0 16px;
  font-size: 14px;
  line-height: 1.5;
  color: var(--on-surface-variant);
}
.muted-strong {
  color: var(--on-surface-variant);
}

.field-label {
  display: block;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.05em;
  text-transform: uppercase;
  color: var(--on-surface-variant);
  margin-bottom: 6px;
}
.control {
  width: 100%;
  box-sizing: border-box;
  height: 40px;
  border: 1px solid var(--outline-variant);
  border-radius: 4px;
  background: var(--surface);
  color: var(--on-surface);
  padding: 0 12px;
  font: inherit;
  outline: none;
  margin-bottom: 12px;
}
.control:focus {
  border-color: var(--primary-container);
  box-shadow: 0 0 0 1px var(--primary-container);
}
.textarea {
  height: auto;
  min-height: 80px;
  padding: 10px 12px;
  line-height: 1.65;
  resize: vertical;
}

.mode-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.mode-item {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  padding: 12px 14px;
  border: 1px solid var(--outline-variant);
  border-radius: var(--radius-lg);
  background: var(--surface);
  cursor: pointer;
  text-align: left;
  transition:
    border-color 0.2s,
    background 0.2s,
    box-shadow 0.2s;
  color: inherit;
  font: inherit;
}
.mode-item:hover {
  border-color: color-mix(in srgb, var(--primary) 35%, var(--outline-variant));
  background: var(--surface-container-low);
}
.mode-item--active {
  border-color: var(--primary-container);
  background: color-mix(in srgb, var(--primary-fixed) 35%, var(--surface-container-lowest));
  box-shadow: var(--shadow-sm);
}
.mode-icon {
  font-size: 22px;
  color: var(--primary-container);
  flex-shrink: 0;
}
.mode-text {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.mode-label {
  font-size: 15px;
  font-weight: 600;
  color: var(--on-surface);
}
.mode-sub {
  font-size: 12px;
  color: var(--on-surface-variant);
  line-height: 1.4;
}
.mode-check {
  font-size: 22px;
  color: var(--primary-container);
  opacity: 0;
  flex-shrink: 0;
  transition: opacity 0.15s;
}
.mode-check.visible {
  opacity: 1;
}

.upload-card--disabled .drop-zone {
  opacity: 0.55;
  pointer-events: none;
}
.upload-card--drag {
  box-shadow: var(--shadow-md);
}
.drop-zone {
  border: 2px dashed color-mix(in srgb, var(--primary-container) 45%, var(--outline-variant));
  border-radius: var(--radius-lg);
  background: color-mix(in srgb, var(--primary-fixed) 22%, var(--surface-container-lowest));
  padding: 20px 16px;
  text-align: center;
  cursor: pointer;
  transition:
    background 0.2s,
    border-color 0.2s;
}
.drop-zone:hover,
.drop-zone:focus {
  outline: none;
  background: color-mix(in srgb, var(--tertiary-fixed) 40%, var(--surface-container-lowest));
  border-color: var(--primary-container);
}
.drop-zone--active {
  background: color-mix(in srgb, var(--primary-fixed) 50%, white);
  border-color: var(--primary-container);
}
.drop-icon {
  font-size: 36px;
  color: var(--primary-container);
  display: block;
  margin: 0 auto 8px;
}
.drop-hint,
.drop-file {
  margin: 0;
  font-size: 14px;
  color: var(--on-surface-variant);
}
.drop-file {
  font-weight: 600;
  color: var(--on-surface);
  word-break: break-all;
}
.file-actions {
  display: flex;
  gap: 12px;
  margin-top: 10px;
  justify-content: center;
}
.text-btn {
  border: none;
  background: none;
  font-size: 13px;
  font-weight: 600;
  color: var(--primary-container);
  cursor: pointer;
  padding: 4px 8px;
}
.text-btn:hover {
  text-decoration: underline;
}
.text-btn--danger {
  color: var(--error);
}

.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}

.main-grid {
  display: grid;
  grid-template-columns: 1fr;
  gap: 24px;
  align-items: stretch;
}
@media (min-width: 1024px) {
  .main-grid {
    gap: 28px;
    /* 压缩数据录入、放大结果预览（约 34% : 66%）；stretch 使两列纵向等高 */
    grid-template-columns: minmax(0, 11fr) minmax(0, 21fr);
    align-items: stretch;
  }
  .main-grid > .panel-form,
  .main-grid > .panel-preview {
    align-self: stretch;
    min-height: 0;
  }
}

.panel {
  border-radius: var(--radius-xl);
  border: 1px solid var(--surface-container-highest);
  background: var(--surface-container-lowest);
  box-shadow: var(--shadow-sm);
}
.panel-form {
  padding: 28px 32px 32px;
}
@media (min-width: 1024px) {
  .panel-form {
    padding: 24px 22px 28px;
  }
  .panel-form.form-panel {
    display: flex;
    flex-direction: column;
    height: 100%;
    min-height: 0;
  }
  .panel-form .form-actions {
    margin-top: auto;
  }
}
.form-panel {
  container-type: inline-size;
  container-name: doc-form;
}
.panel-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding-bottom: 16px;
  margin-bottom: 20px;
  border-bottom: 1px solid var(--surface-container-highest);
}
.panel-title {
  margin: 0 0 4px;
  font-size: 22px;
  font-weight: 600;
  color: var(--on-surface);
}
.panel-sub {
  margin: 0;
  font-size: 14px;
  color: var(--on-surface-variant);
}
.icon-only {
  width: 40px;
  height: 40px;
  border: none;
  border-radius: 999px;
  background: transparent;
  color: var(--on-surface-variant);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition:
    background 0.2s,
    color 0.2s;
}
.icon-only:hover {
  color: var(--primary-container);
  background: var(--surface-container-low);
}

.section-heading {
  margin: 0 0 12px;
  font-size: 15px;
  font-weight: 600;
  color: var(--on-surface);
  display: flex;
  align-items: center;
  gap: 8px;
}
.section-heading .sm {
  font-size: 18px;
  color: var(--primary-container);
}

.block {
  padding-top: 20px;
  margin-top: 20px;
  border-top: 1px solid var(--surface-container-highest);
}
.block:first-of-type {
  border-top: none;
  margin-top: 0;
  padding-top: 0;
}
.field-grid {
  display: grid;
  grid-template-columns: 1fr;
  gap: 12px;
}
@container doc-form (min-width: 400px) {
  .form-panel .field-grid {
    grid-template-columns: 1fr 1fr;
  }
}
.stack {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.mini-label {
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.05em;
  text-transform: uppercase;
  color: var(--on-surface-variant);
}

.notice {
  margin-top: 16px;
  border-radius: var(--radius-lg);
  padding: 12px 14px;
  font-size: 14px;
}
.notice.error {
  color: var(--on-error-container);
  background: var(--error-container);
}

.form-actions {
  margin-top: 24px;
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  flex-wrap: wrap;
}
.btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-height: 40px;
  padding: 0 18px;
  border-radius: 4px;
  font-size: 15px;
  font-weight: 600;
  cursor: pointer;
  border: 1px solid transparent;
  transition:
    background 0.2s,
    box-shadow 0.2s,
    border-color 0.2s;
}
.btn:disabled {
  opacity: 0.65;
  cursor: wait;
}
.btn.ghost {
  background: transparent;
  border-color: var(--primary-container);
  color: var(--primary-container);
}
.btn.ghost:hover {
  background: var(--surface-container-low);
}
.btn.primary {
  background: var(--primary-container);
  color: var(--on-primary);
  box-shadow: var(--shadow-sm);
  border-color: transparent;
}
.btn.primary:hover {
  background: var(--primary);
  box-shadow: var(--shadow-md);
}
.btn-ic {
  font-size: 18px;
}

.panel-preview {
  padding: 0;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  min-height: 480px;
  min-width: 0;
  border: 1px solid color-mix(in srgb, var(--primary-container) 14%, var(--surface-container-highest));
  box-shadow: var(--shadow-md);
}
@media (min-width: 1024px) {
  /* 用 grid 占满被拉伸的单元格，避免右侧壳层高度短于左侧表单 */
  .panel-preview {
    display: grid;
    grid-template-columns: minmax(0, 1fr);
    grid-template-rows: minmax(0, 1fr);
    min-height: 0;
    height: 100%;
  }
}
.preview-shell {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 520px;
  min-width: 0;
  background: linear-gradient(180deg, var(--surface-container-low) 0%, var(--surface-bright) 48%, var(--surface-bright) 100%);
  border-radius: inherit;
}
@media (min-width: 1024px) {
  .preview-shell {
    grid-column: 1;
    grid-row: 1;
    min-height: 0;
    height: 100%;
    flex: unset;
  }
}
.preview-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 18px 22px;
  background: var(--surface-container-lowest);
  border-bottom: 1px solid var(--surface-container-highest);
}
.preview-toolbar-left {
  display: flex;
  align-items: center;
  gap: 8px;
}
.preview-toolbar-icon {
  color: var(--primary-container);
  font-size: 22px;
}
.preview-toolbar-title {
  margin: 0;
  font-size: 17px;
  font-weight: 600;
  letter-spacing: -0.01em;
  color: var(--on-surface);
}
.status-pill {
  font-size: 12px;
  font-weight: 600;
  padding: 4px 10px;
  border-radius: 999px;
  background: var(--surface-container-low);
  color: var(--on-surface-variant);
}
.preview-body {
  --preview-inner-max: min(820px, 100%);
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  overflow-x: hidden;
  overflow-y: auto;
  padding: 22px 24px 28px;
  background: var(--surface-container);
}
.paper {
  box-sizing: border-box;
  width: 100%;
  max-width: var(--preview-inner-max, min(820px, 100%));
  min-width: 0;
  margin: 0 auto 22px;
  min-height: 240px;
  flex: 1 1 auto;
  display: flex;
  flex-direction: column;
  padding: 30px 28px;
  background: var(--surface-container-lowest);
  border: 1px solid var(--surface-variant);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
}
.preview-body > .sub-card,
.preview-body > .foot-grid {
  flex-shrink: 0;
}
.paper-title {
  margin: 0 0 16px;
  font-size: 18px;
  font-weight: 700;
  text-align: center;
  letter-spacing: 0.12em;
  color: var(--on-surface);
  overflow-wrap: anywhere;
  word-break: break-word;
}
.paper-content {
  margin: 0;
  word-break: break-word;
  font-family: inherit;
  font-size: 14px;
  line-height: 1.75;
  color: var(--on-surface);
}
.paper-content.doc-plain--placeholder {
  white-space: pre-wrap;
  color: var(--on-surface-variant);
}
.paper-content.doc-plain:not(.doc-plain--placeholder) {
  white-space: pre-wrap;
  word-break: break-word;
  text-align: justify;
  text-justify: inter-ideograph;
  line-height: 2;
  font-size: 15px;
  color: var(--on-surface);
}
.plain-bullet-list {
  list-style: disc;
  padding-left: 1.25em;
  margin: 0;
}
.plain-line {
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.65;
  margin: 0.35em 0;
}
.tags-md {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.plain-chip {
  box-sizing: border-box;
  min-width: 0;
  max-width: 100%;
  font-size: 13px;
  line-height: 1.55;
  color: var(--on-surface-variant);
  padding: 10px 12px;
  border-radius: var(--radius-md);
  background: var(--surface-container-lowest);
  border: 1px solid var(--surface-variant);
  overflow-wrap: anywhere;
  word-break: break-word;
  white-space: pre-wrap;
}

.sub-card {
  box-sizing: border-box;
  width: 100%;
  max-width: var(--preview-inner-max, min(820px, 100%));
  min-width: 0;
  margin: 0 auto 22px;
  padding: 18px 20px;
  background: var(--surface-container-lowest);
  border: 1px solid var(--primary-fixed);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
}
.sub-card h4 {
  margin: 0 0 10px;
  font-size: 14px;
  font-weight: 600;
  color: var(--primary-container);
  display: flex;
  align-items: center;
  gap: 6px;
}
.sub-card p,
.sub-card li {
  margin: 0;
  font-size: 14px;
  line-height: 1.65;
  color: var(--on-surface-variant);
}
.sub-card ul {
  margin: 0;
  padding-left: 18px;
}

.foot-grid {
  box-sizing: border-box;
  width: 100%;
  max-width: var(--preview-inner-max, min(820px, 100%));
  min-width: 0;
  margin: 0 auto;
  display: grid;
  grid-template-columns: 1fr;
  gap: 18px;
}
.foot-grid > div {
  min-width: 0;
}
@media (min-width: 560px) {
  .foot-grid {
    grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  }
}
.foot-grid h4 {
  margin: 0 0 8px;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  color: var(--secondary);
  display: flex;
  align-items: center;
  gap: 6px;
}
.tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.tags span {
  font-size: 12px;
  color: var(--on-surface-variant);
  padding: 6px 10px;
  border-radius: 999px;
  background: var(--surface-container-lowest);
  border: 1px solid var(--surface-variant);
}
.tags-empty {
  font-style: italic;
  border-style: dashed;
}

.preview-footer {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 18px 22px 20px;
  background: var(--surface-container-lowest);
  border-top: 1px solid var(--surface-container-highest);
}
.footer-hint {
  font-size: 13px;
  color: var(--on-surface-variant);
  text-align: center;
}
.footer-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: center;
  gap: 12px 16px;
}
.export-dropdown-trigger {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.export-chev {
  font-size: 20px;
  margin-left: 2px;
}
.footer-actions :deep(.el-dropdown) {
  vertical-align: middle;
}
.btn.ghost:disabled,
.btn.primary:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

@media (max-width: 640px) {
  .wrap {
    padding: 16px 16px 32px;
  }
  .hero {
    padding: 22px 18px;
  }
  .h1 {
    font-size: 28px;
  }
  .sub {
    font-size: 16px;
  }
  .panel-form {
    padding: 20px 18px 24px;
  }
  .footer-actions {
    flex-direction: column;
  }
}
</style>
