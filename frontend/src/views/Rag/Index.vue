<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import AppLayout from '../../components/common/AppLayout.vue'
import {
  answerRag,
  bootstrapRagFoundation,
  deleteRagDocument,
  getRagMeta,
  ingestRagText,
  listRagDocuments,
  searchRag,
  uploadRagDocument,
} from '../../api/rag'

const loading = ref(false)
const uploading = ref(false)
const bootstrapping = ref(false)
const error = ref('')
const message = ref('')
const fileInput = ref(null)
const selectedFile = ref(null)

const scopeOptions = [
  { label: '全部模块', value: 'all' },
  { label: '智能咨询', value: 'chat' },
  { label: '法条检索', value: 'law' },
  { label: '文书生成', value: 'document' },
  { label: '合同审查', value: 'contract' },
  { label: '案例匹配', value: 'case' },
  { label: '流程指引', value: 'guide' },
]

const meta = ref({
  documents: 0,
  chunks: 0,
  vectorEnabled: false,
  embeddingModel: '',
  lastUpdatedAt: null,
  moduleScopes: [],
  businessTypes: [],
  statusStats: {},
})

const docs = ref([])
const searchResponse = ref({ items: [] })
const answer = ref(null)

const uploadForm = reactive({
  title: '',
  moduleScope: 'all',
  businessType: '通用法律知识',
  sourceType: 'upload',
  tags: '',
})

const textForm = reactive({
  title: '',
  moduleScope: 'all',
  businessType: '通用法律知识',
  sourceType: 'manual',
  tags: '',
  text: '',
})

const queryForm = reactive({
  q: '',
  moduleScope: 'all',
  businessType: '',
  limit: 6,
})

const docStatusText = computed(() => {
  const stats = meta.value.statusStats || {}
  return Object.entries(stats)
    .map(([k, v]) => `${k}: ${v}`)
    .join('，') || '暂无索引'
})

function showError(e) {
  error.value = e?.message || '操作失败'
  message.value = ''
}

function showMessage(text) {
  message.value = text
  error.value = ''
}

async function refresh() {
  loading.value = true
  try {
    const [m, list] = await Promise.all([getRagMeta(), listRagDocuments({ size: 20 })])
    meta.value = m
    docs.value = list || []
  } catch (e) {
    showError(e)
  } finally {
    loading.value = false
  }
}

function chooseFile() {
  fileInput.value?.click()
}

function onFileChange(event) {
  selectedFile.value = event.target.files?.[0] || null
  if (selectedFile.value && !uploadForm.title) {
    uploadForm.title = selectedFile.value.name
  }
}

async function submitUpload() {
  if (!selectedFile.value) {
    showError(new Error('请选择要入库的文件'))
    return
  }
  uploading.value = true
  try {
    const fd = new FormData()
    fd.append('file', selectedFile.value)
    Object.entries(uploadForm).forEach(([k, v]) => fd.append(k, v || ''))
    await uploadRagDocument(fd)
    selectedFile.value = null
    if (fileInput.value) fileInput.value.value = ''
    showMessage('文档已完成解析、切片并写入知识库')
    await refresh()
  } catch (e) {
    showError(e)
  } finally {
    uploading.value = false
  }
}

async function submitText() {
  if (!textForm.text.trim()) {
    showError(new Error('请输入要入库的文本'))
    return
  }
  loading.value = true
  try {
    await ingestRagText({ ...textForm })
    textForm.text = ''
    showMessage('文本已写入知识库')
    await refresh()
  } catch (e) {
    showError(e)
  } finally {
    loading.value = false
  }
}

async function runSearch() {
  loading.value = true
  answer.value = null
  try {
    searchResponse.value = await searchRag(queryForm)
  } catch (e) {
    showError(e)
  } finally {
    loading.value = false
  }
}

async function runAnswer() {
  if (!queryForm.q.trim()) {
    showError(new Error('请输入问题'))
    return
  }
  loading.value = true
  try {
    answer.value = await answerRag({
      question: queryForm.q,
      moduleScope: queryForm.moduleScope,
      businessType: queryForm.businessType,
      limit: queryForm.limit,
    })
    searchResponse.value = { items: answer.value.references || [] }
  } catch (e) {
    showError(e)
  } finally {
    loading.value = false
  }
}

async function bootstrap() {
  bootstrapping.value = true
  try {
    const result = await bootstrapRagFoundation()
    showMessage(`已同步基础库：法条 ${result.laws || 0} 条，案例 ${result.cases || 0} 条`)
    await refresh()
  } catch (e) {
    showError(e)
  } finally {
    bootstrapping.value = false
  }
}

async function removeDoc(doc) {
  loading.value = true
  try {
    await deleteRagDocument(doc.docId)
    showMessage('文档已移出知识库')
    await refresh()
  } catch (e) {
    showError(e)
  } finally {
    loading.value = false
  }
}

function fmtTime(t) {
  return t ? String(t).replace('T', ' ').slice(0, 19) : '暂无'
}

onMounted(refresh)
</script>

<template>
  <AppLayout>
    <div class="page">
      <div class="wrap">
        <div class="topHead">
          <div>
            <p class="eyebrow">RAG KNOWLEDGE BASE</p>
            <h1>法律知识根基</h1>
            <p>上传法规、案例与内部资料，供各模块检索引用。</p>
          </div>
          <button class="primaryBtn" type="button" :disabled="bootstrapping" @click="bootstrap">
            <span class="material-symbols-outlined">hub</span>
            {{ bootstrapping ? '同步中' : '同步法条与案例' }}
          </button>
        </div>

        <div v-if="error" class="alert error">{{ error }}</div>
        <div v-if="message" class="alert ok">{{ message }}</div>

        <div class="stats">
          <div class="stat">
            <span>文档数</span>
            <strong>{{ meta.documents }}</strong>
          </div>
          <div class="stat">
            <span>切片数</span>
            <strong>{{ meta.chunks }}</strong>
          </div>
          <div class="stat">
            <span>向量检索</span>
            <strong class="small">{{ meta.vectorEnabled ? '已开启' : '未开启' }}</strong>
          </div>
          <div class="stat">
            <span>索引状态</span>
            <strong class="small">{{ docStatusText }}</strong>
          </div>
        </div>

        <div class="grid">
          <section class="panel">
            <div class="panelHead">
              <h2>入库</h2>
              <span>{{ fmtTime(meta.lastUpdatedAt) }}</span>
            </div>

            <div class="uploadBox" role="button" tabindex="0" @click="chooseFile">
              <span class="material-symbols-outlined">cloud_upload</span>
              <strong>{{ selectedFile?.name || '选择 PDF、Word、TXT 文件' }}</strong>
              <small>上传后自动解析入库</small>
              <input ref="fileInput" hidden type="file" @change="onFileChange" />
            </div>

            <div class="formGrid">
              <input v-model="uploadForm.title" placeholder="文档标题" />
              <select v-model="uploadForm.moduleScope">
                <option v-for="item in scopeOptions" :key="item.value" :value="item.value">{{ item.label }}</option>
              </select>
              <input v-model="uploadForm.businessType" placeholder="业务类型，如劳动争议、合同模板" />
              <input v-model="uploadForm.tags" placeholder="标签，多个用逗号分隔" />
            </div>
            <button class="primaryBtn full" type="button" :disabled="uploading" @click="submitUpload">
              <span class="material-symbols-outlined">upload_file</span>
              {{ uploading ? '处理中' : '上传入库' }}
            </button>

            <div class="textIngest">
              <textarea v-model="textForm.text" rows="6" placeholder="也可以粘贴法规、案例要点、合同条款或流程规范直接入库"></textarea>
              <div class="formGrid">
                <input v-model="textForm.title" placeholder="文本标题" />
                <select v-model="textForm.moduleScope">
                  <option v-for="item in scopeOptions" :key="item.value" :value="item.value">{{ item.label }}</option>
                </select>
                <input v-model="textForm.businessType" placeholder="业务类型" />
                <input v-model="textForm.tags" placeholder="标签" />
              </div>
              <button class="ghostBtn full" type="button" @click="submitText">保存文本</button>
            </div>
          </section>

          <section class="panel">
            <div class="panelHead">
              <h2>检索与问答</h2>
              <span>{{ searchResponse.items?.length || 0 }} 条引用</span>
            </div>
            <textarea v-model="queryForm.q" rows="5" placeholder="输入问题，例如：公司拖欠工资三个月，仲裁需要准备哪些材料？"></textarea>
            <div class="queryTools">
              <select v-model="queryForm.moduleScope">
                <option v-for="item in scopeOptions" :key="item.value" :value="item.value">{{ item.label }}</option>
              </select>
              <input v-model="queryForm.businessType" placeholder="业务类型筛选，可为空" />
              <input v-model.number="queryForm.limit" type="number" min="1" max="20" />
            </div>
            <div class="actions">
              <button class="ghostBtn" type="button" :disabled="loading" @click="runSearch">
                <span class="material-symbols-outlined">search</span>
                检索片段
              </button>
              <button class="primaryBtn" type="button" :disabled="loading" @click="runAnswer">
                <span class="material-symbols-outlined">psychology_alt</span>
                RAG问答
              </button>
            </div>

            <div v-if="answer" class="answer">
              <h3>回答</h3>
              <p>{{ answer.answer }}</p>
            </div>

            <div class="refs">
              <article v-for="item in searchResponse.items" :key="item.chunkId" class="ref">
                <div class="refHead">
                  <strong>{{ item.documentTitle }}</strong>
                  <span>{{ item.scoreLabel }} · {{ item.score }}</span>
                </div>
                <p>{{ item.content }}</p>
                <small>{{ item.moduleScope }} / {{ item.businessType }} / 片段 {{ item.chunkIndex }}</small>
              </article>
            </div>
          </section>
        </div>

        <section class="panel tablePanel">
          <div class="panelHead">
            <h2>最近入库</h2>
            <button class="ghostBtn sm" type="button" @click="refresh">刷新</button>
          </div>
          <div class="tableWrap">
            <table>
              <thead>
                <tr>
                  <th>文档</th>
                  <th>模块</th>
                  <th>类型</th>
                  <th>切片</th>
                  <th>状态</th>
                  <th>更新时间</th>
                  <th class="right">操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="doc in docs" :key="doc.docId">
                  <td>
                    <strong>{{ doc.title }}</strong>
                    <small>{{ doc.originalName }}</small>
                  </td>
                  <td>{{ doc.moduleScope }}</td>
                  <td>{{ doc.businessType }}</td>
                  <td>{{ doc.chunkCount }}</td>
                  <td><span class="status">{{ doc.status }}</span></td>
                  <td>{{ fmtTime(doc.updatedAt) }}</td>
                  <td class="right">
                    <button class="iconBtn" type="button" title="删除" @click="removeDoc(doc)">
                      <span class="material-symbols-outlined">delete</span>
                    </button>
                  </td>
                </tr>
                <tr v-if="!docs.length">
                  <td colspan="7" class="empty">暂无知识库文档</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>
      </div>
    </div>
  </AppLayout>
</template>

<style scoped>
.page { height: 100%; overflow: auto; }
.wrap { max-width: 1280px; margin: 0 auto; padding: 24px; }
.topHead { display: flex; align-items: flex-end; justify-content: space-between; gap: 18px; margin-bottom: 18px; }
.eyebrow { margin: 0 0 6px; font-size: 12px; letter-spacing: 0.12em; color: var(--secondary); font-weight: 700; }
.topHead h1 { margin: 0 0 8px; font-size: 36px; color: var(--on-surface); }
.topHead p:last-child { margin: 0; color: var(--on-surface-variant); line-height: 1.7; }
.alert { border-radius: 8px; padding: 10px 12px; margin-bottom: 12px; font-size: 14px; }
.alert.error { background: #fef2f2; color: #b91c1c; }
.alert.ok { background: #ecfdf3; color: #15803d; }
.stats { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; margin-bottom: 18px; }
.stat, .panel {
  background: var(--surface-container-lowest);
  border: 1px solid var(--surface-variant);
  border-radius: 8px;
  box-shadow: var(--shadow-sm);
}
.stat { padding: 16px; display: flex; flex-direction: column; gap: 8px; min-width: 0; }
.stat span { color: var(--secondary); font-size: 13px; }
.stat strong { color: var(--primary-container); font-size: 30px; line-height: 1.1; }
.stat strong.small { font-size: 16px; color: var(--on-surface); overflow-wrap: anywhere; }
.grid { display: grid; grid-template-columns: 1fr 1.25fr; gap: 18px; align-items: start; }
.panel { padding: 18px; }
.panelHead { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 14px; }
.panelHead h2 { margin: 0; font-size: 20px; color: var(--on-surface); }
.panelHead span { color: var(--secondary); font-size: 13px; }
.uploadBox {
  border: 2px dashed color-mix(in srgb, var(--primary-container) 45%, transparent);
  background: color-mix(in srgb, #dbeafe 35%, transparent);
  border-radius: 8px;
  min-height: 132px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  text-align: center;
  cursor: pointer;
  margin-bottom: 12px;
}
.uploadBox .material-symbols-outlined { font-size: 34px; color: var(--primary-container); }
.uploadBox small, td small { display: block; color: var(--on-surface-variant); margin-top: 4px; }
.formGrid, .queryTools { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; margin-bottom: 12px; }
.queryTools { grid-template-columns: 160px 1fr 80px; }
input, select, textarea {
  width: 100%;
  border: 1px solid var(--outline-variant);
  border-radius: 8px;
  background: var(--surface);
  color: var(--on-surface);
  font-size: 14px;
  padding: 10px 12px;
  outline: none;
}
textarea { resize: vertical; line-height: 1.7; }
input:focus, select:focus, textarea:focus { border-color: var(--primary-container); box-shadow: 0 0 0 1px var(--primary-container); }
.primaryBtn, .ghostBtn {
  height: 40px;
  border-radius: 8px;
  padding: 0 14px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  cursor: pointer;
  font-weight: 700;
}
.primaryBtn { border: none; background: var(--primary-container); color: var(--on-primary); }
.ghostBtn { border: 1px solid var(--outline-variant); background: var(--surface); color: var(--primary); }
.primaryBtn:disabled, .ghostBtn:disabled { opacity: 0.6; cursor: not-allowed; }
.full { width: 100%; }
.sm { height: 32px; font-size: 13px; }
.textIngest { border-top: 1px solid var(--surface-variant); margin-top: 16px; padding-top: 16px; }
.actions { display: flex; gap: 10px; justify-content: flex-end; margin-bottom: 12px; }
.answer { background: var(--surface-container-low); border-radius: 8px; padding: 14px; margin-bottom: 12px; }
.answer h3 { margin: 0 0 8px; font-size: 16px; }
.answer p { white-space: pre-wrap; margin: 0; line-height: 1.7; }
.refs { display: flex; flex-direction: column; gap: 10px; max-height: 520px; overflow: auto; padding-right: 4px; }
.ref { border: 1px solid var(--surface-variant); border-radius: 8px; padding: 12px; }
.refHead { display: flex; justify-content: space-between; gap: 10px; margin-bottom: 8px; }
.refHead span, .ref small { color: var(--secondary); font-size: 12px; }
.ref p { margin: 0 0 8px; color: var(--on-surface-variant); line-height: 1.7; max-height: 100px; overflow: hidden; }
.tablePanel { margin-top: 18px; }
.tableWrap { overflow-x: auto; }
table { width: 100%; border-collapse: collapse; min-width: 850px; }
th, td { padding: 12px; border-top: 1px solid var(--surface-variant); text-align: left; font-size: 14px; vertical-align: top; }
th { color: var(--secondary); font-size: 12px; }
.status { display: inline-flex; border-radius: 999px; background: #ecfdf3; color: #15803d; padding: 4px 9px; font-size: 12px; font-weight: 700; }
.right { text-align: right; }
.iconBtn { border: none; background: transparent; color: var(--primary); cursor: pointer; }
.iconBtn .material-symbols-outlined { font-size: 20px; }
.empty { color: var(--on-surface-variant); text-align: center; }
@media (max-width: 1000px) {
  .topHead { flex-direction: column; align-items: flex-start; }
  .stats, .grid { grid-template-columns: 1fr; }
  .queryTools, .formGrid { grid-template-columns: 1fr; }
}
</style>
