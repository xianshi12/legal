<script setup>
import AppLayout from '../../components/common/AppLayout.vue'
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { reviewContract } from '../../api/contract'

const router = useRouter()
const loading = ref(false)
const error = ref('')
const selectedFile = ref(null)
const fileInputRef = ref(null)
const uploadDragOver = ref(false)
const activeTab = ref('risks')
const form = ref({
  contractName: '',
  contractType: '租赁合同',
  reviewMode: 'full',
  reviewFocus: '违约责任、押金/保证金、格式条款提示、解除终止、争议解决',
  rawText: '',
})
const result = ref(null)

const acceptTypes = '.txt,.docx,.pdf,.doc'

const sampleText = `房屋租赁合同
甲方如需提前解除合同，仅需提前3日通知乙方，无需承担任何违约责任；乙方提前解除合同需支付6个月租金作为违约金。
乙方向甲方支付押金人民币5000元，合同终止后如乙方有任何违约行为，押金不退。
如因房屋设施产生任何损失，甲方不承担任何责任。
租金按月支付，双方未约定争议解决方式。`

const risks = computed(() => result.value?.risks || [])
const revisions = computed(() => result.value?.revisions || [])
const riskStats = computed(() => result.value?.riskStats || { 高风险: 0, 中风险: 0, 低风险: 0 })

/** 风险清单：默认只看高风险；null 表示展示全部 */
const riskListLevelFilter = ref('高风险')

const filteredRiskRows = computed(() => {
  const list = risks.value
  const lv = riskListLevelFilter.value
  return list
    .map((item, originalIndex) => ({ item, originalIndex }))
    .filter(({ item }) => lv === null || item.level === lv)
})

function setRiskListTier(level) {
  riskListLevelFilter.value = level
}

function showAllRisksInList() {
  riskListLevelFilter.value = null
}

function tierCardActive(level) {
  return riskListLevelFilter.value === level
}

/** 条款级「修改前→修改后」依次替换得到的修订全文（仅正文） */
function applyRevisionsToSource(source, revs) {
  if (!source || !revs?.length) return source || ''
  let out = source
  for (const r of revs) {
    const b = String(r.beforeText ?? '').trim()
    const a = String(r.afterText ?? '').trim()
    if (b.length < 2 || !a) continue
    const idx = out.indexOf(b)
    if (idx >= 0) out = out.slice(0, idx) + a + out.slice(idx + b.length)
  }
  return out
}

/** 后端兜底 buildRevisedText 等混有「摘要/修改前/原因」的打包文本，不作为主正文展示 */
function isPackedRevisionBlob(text) {
  if (!text || text.length < 40) return false
  return (
    text.includes('合同修订建议摘要') ||
    (text.includes('修改前：') && text.includes('修改后：') && text.includes('原因：'))
  )
}

const revisedPlainFull = computed(() => {
  const src = (form.value.rawText || result.value?.sourceText || '').trim()
  if (!result.value) return ''
  const merged = applyRevisionsToSource(src, revisions.value)
  if (merged && merged !== src) return merged
  const api = (result.value.revisedText || '').trim()
  if (api && !isPackedRevisionBlob(api)) return api
  if (merged) return merged
  return src
})

/** 模型返回的打包说明或附录，展示在正文下方 */
const revisedTextAppendix = computed(() => {
  const api = (result.value?.revisedText || '').trim()
  if (!api) return ''
  if (!isPackedRevisionBlob(api)) return ''
  return api
})

const leftScrollEl = ref(null)
const rightScrollEl = ref(null)
let scrollSyncing = false
function onCompareScroll(from) {
  if (activeTab.value !== 'compare') return
  if (scrollSyncing) return
  const a = from === 'left' ? leftScrollEl.value : rightScrollEl.value
  const b = from === 'left' ? rightScrollEl.value : leftScrollEl.value
  if (!a || !b) return
  scrollSyncing = true
  b.scrollTop = a.scrollTop
  requestAnimationFrame(() => {
    scrollSyncing = false
  })
}

function escapeHtml(s) {
  if (!s) return ''
  return String(s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

function escapeAttr(s) {
  return escapeHtml(s).replace(/'/g, '&#39;')
}

/**
 * 修订侧展示用：统一换行；段落之间保留「空一行」（即 \n\n）；仅把 3 个以上连续换行压成 \n\n。
 * 并对常见书面层次（章节号、当事人块等）在「单换行」处补成段前空一行，便于与左侧合同对齐。
 * 不改变 copy 用的原始 revisedPlainFull。
 */
function formatRevisedBodyForDisplay(raw) {
  let s = String(raw ?? '')
    .replace(/\r\n/g, '\n')
    .replace(/\r/g, '\n')
    .replace(/[ \t]+\n/g, '\n')
  s = s.replace(/\n{3,}/g, '\n\n')
  s = enhanceContractBlankLineHierarchy(s)
  s = s.replace(/\n{3,}/g, '\n\n')
  return s
}

/** 单换行 → 双换行：仅当上一字符不是换行（避免已是空行再叠） */
function enhanceContractBlankLineHierarchy(s) {
  let t = s
  t = t.replace(/(.)\n([一二三四五六七八九十百千零〇]+、[^\n]*)/g, (full, prev, rest) => {
    if (prev === '\n') return full
    return `${prev}\n\n${rest}`
  })
  t = t.replace(/([。；])\n(劳动者|乙方|甲方|用人单位|法定代表人|身份证号码|住所地|联系电话)/g, '$1\n\n$2')
  t = t.replace(/([:：])\n([一二三四五六七八九十]+、)/g, '$1\n\n$2')
  return t
}

function normalizeRevisionFragment(raw) {
  return String(raw ?? '')
    .replace(/\r\n/g, '\n')
    .replace(/\r/g, '\n')
    .replace(/[ \t]+\n/g, '\n')
    .trim()
}

function buildAnnotatedContractHtml(text, riskItems) {
  if (!text) return ''
  if (!riskItems.length) return escapeHtml(text).replace(/\n/g, '<br />')

  const marks = []
  for (const r of riskItems) {
    const frag = String(r.originalClause || r.beforeText || '').trim()
    if (frag.length < 2) continue
    let pos = 0
    while (pos < text.length) {
      const i = text.indexOf(frag, pos)
      if (i < 0) break
      marks.push({
        start: i,
        end: i + frag.length,
        level: r.level,
        tip: (r.suggestion || r.issue || '建议修改').slice(0, 280),
      })
      pos = i + frag.length
    }
  }
  marks.sort((a, b) => a.start - b.start)
  const chosen = []
  for (const m of marks) {
    if (chosen.length && m.start < chosen[chosen.length - 1].end) continue
    chosen.push(m)
  }

  let out = ''
  let cursor = 0
  for (const m of chosen) {
    out += escapeHtml(text.slice(cursor, m.start)).replace(/\n/g, '<br />')
    const cls =
      m.level === '高风险' ? 'rm-high' : m.level === '中风险' ? 'rm-med' : 'rm-low'
    const label = m.level === '高风险' ? '高风险' : m.level === '中风险' ? '中风险' : '低风险'
    const tip = escapeAttr(m.tip)
    const mid = escapeHtml(text.slice(m.start, m.end)).replace(/\n/g, '<br />')
    out += `<span class="rm ${cls}" title="${tip}"><span class="rm-label">${label}</span>${mid}</span>`
    cursor = m.end
  }
  out += escapeHtml(text.slice(cursor)).replace(/\n/g, '<br />')
  return out
}

/** 风险清单页：正文标注随档位筛选变化 */
const annotatedContractHtmlForRiskList = computed(() =>
  buildAnnotatedContractHtml(
    form.value.rawText || '',
    riskListLevelFilter.value === null
      ? risks.value
      : risks.value.filter((r) => r.level === riskListLevelFilter.value),
  ),
)

/** 对比修订页：始终标注全部风险 */
const annotatedContractHtmlForCompare = computed(() =>
  buildAnnotatedContractHtml(form.value.rawText || '', risks.value),
)

function resolveFragInDisplayText(text, fragRaw) {
  let frag = normalizeRevisionFragment(fragRaw)
  if (frag.length >= 2 && !text.includes(frag)) {
    const alt = formatRevisedBodyForDisplay(frag)
    if (text.includes(alt)) frag = alt
  }
  return frag
}

function collectRevisionOccurrences(text, frag, tip, kind) {
  const list = []
  if (frag.length < 2) return list
  let pos = 0
  while (pos < text.length) {
    const i = text.indexOf(frag, pos)
    if (i < 0) break
    list.push({ start: i, end: i + frag.length, kind, tip })
    pos = i + frag.length
  }
  return list
}

function pickNonOverlappingMarks(sortedMarks) {
  const chosen = []
  for (const m of sortedMarks) {
    if (chosen.length && m.start < chosen[chosen.length - 1].end) continue
    chosen.push(m)
  }
  return chosen
}

/** 修订全文：修改后（绿）+ 仍出现在文中的修改前（橙）下划线；与高中低筛选无关 */
const annotatedRevisedHtml = computed(() => {
  const raw = revisedPlainFull.value || ''
  if (!raw) return ''
  const text = formatRevisedBodyForDisplay(raw)
  if (!revisions.value.length) return escapeHtml(text).replace(/\n/g, '<br />')

  const afterMarks = []
  const beforeMarks = []
  for (const r of revisions.value) {
    const tipBase = (r.reason || r.location || '').slice(0, 220)
    const afrag = resolveFragInDisplayText(text, String(r.afterText ?? ''))
    afterMarks.push(
      ...collectRevisionOccurrences(
        text,
        afrag,
        (tipBase || '修订后').slice(0, 280),
        'after',
      ),
    )
    const bfrag = resolveFragInDisplayText(text, String(r.beforeText ?? ''))
    beforeMarks.push(
      ...collectRevisionOccurrences(
        text,
        bfrag,
        (tipBase || '修订前').slice(0, 280),
        'before',
      ),
    )
  }
  afterMarks.sort((a, b) => a.start - b.start)
  beforeMarks.sort((a, b) => a.start - b.start)
  const afterChosen = pickNonOverlappingMarks(afterMarks)
  const beforeFiltered = pickNonOverlappingMarks(beforeMarks).filter(
    (m) => !afterChosen.some((a) => m.start < a.end && m.end > a.start),
  )
  const merged = [...afterChosen, ...beforeFiltered].sort((a, b) => a.start - b.start)
  const chosen = pickNonOverlappingMarks(merged)

  let out = ''
  let cursor = 0
  for (const m of chosen) {
    out += escapeHtml(text.slice(cursor, m.start)).replace(/\n/g, '<br />')
    const tip = escapeAttr(m.tip)
    const mid = escapeHtml(text.slice(m.start, m.end)).replace(/\n/g, '<br />')
    const cls = m.kind === 'before' ? 'rm-revised-before' : 'rm-revised'
    out += `<span class="${cls}" title="${tip}">${mid}</span>`
    cursor = m.end
  }
  out += escapeHtml(text.slice(cursor)).replace(/\n/g, '<br />')
  return out
})

function onFileChange(event) {
  setFile(event.target.files?.[0] || null)
}

async function setFile(file) {
  selectedFile.value = file || null
  if (!form.value.contractName && selectedFile.value) {
    form.value.contractName = selectedFile.value.name
  }
  if (file && /\.txt$/i.test(file.name)) {
    try {
      form.value.rawText = await file.text()
    } catch {
      /* 忽略预读失败，审查时仍由后端解析 */
    }
  }
}

function openFilePicker() {
  fileInputRef.value?.click()
}

function onUploadDragOver(e) {
  e.preventDefault()
  uploadDragOver.value = true
}

function onUploadDragLeave() {
  uploadDragOver.value = false
}

function onUploadDrop(e) {
  e.preventDefault()
  uploadDragOver.value = false
  const f = e.dataTransfer?.files?.[0]
  if (f) setFile(f)
}

async function submit() {
  loading.value = true
  error.value = ''
  try {
    result.value = await reviewContract({ ...form.value, file: selectedFile.value })
    if (result.value?.sourceText) {
      form.value.rawText = result.value.sourceText
    }
    activeTab.value = 'risks'
    riskListLevelFilter.value = '高风险'
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

function reset() {
  selectedFile.value = null
  if (fileInputRef.value) fileInputRef.value.value = ''
  form.value = {
    contractName: '',
    contractType: '租赁合同',
    reviewMode: 'full',
    reviewFocus: '违约责任、押金/保证金、格式条款提示、解除终止、争议解决',
    rawText: '',
  }
  result.value = null
  error.value = ''
  riskListLevelFilter.value = '高风险'
}

function fillSample() {
  form.value.contractName = '房屋租赁合同'
  form.value.contractType = '租赁合同'
  form.value.rawText = sampleText
}

function copyRevised() {
  const t = revisedPlainFull.value
  if (!t) return
  navigator.clipboard?.writeText(t)
}

function levelClass(level) {
  if (level === '高风险') return 'high'
  if (level === '中风险') return 'medium'
  return 'low'
}
</script>

<template>
  <AppLayout>
    <div class="page">
      <div class="wrap">
        <div class="crumbs">
          <a class="link" href="#" @click.prevent="router.push('/dashboard')">首页</a>
          <span class="material-symbols-outlined chev">chevron_right</span>
          <span>法律模块</span>
          <span class="material-symbols-outlined chev">chevron_right</span>
          <span class="here">合同审查</span>
        </div>

        <!-- 描述区 -->
        <div class="hero">
          <div class="heroInner">
            <h1 class="h1">合同风险审查</h1>
            <p class="sub">
              上传 TXT、DOCX、PDF（支持拖拽），或粘贴合同全文；识别高/中/低风险条款。风险清单默认优先展示高风险，可切换档位或展示全部；正文与清单随页面滚动查看。
            </p>
          </div>
          <div class="heroRow">
            <select v-model="form.contractType" class="typeSelect" aria-label="合同类型">
              <option>买卖合同</option>
              <option>租赁合同</option>
              <option>服务合同</option>
              <option>劳动合同</option>
              <option>软件开发合同</option>
              <option>股权/投资合同</option>
              <option>通用合同</option>
            </select>
            <div class="searchBox">
              <span class="material-symbols-outlined searchIcon">contract</span>
              <input
                v-model.trim="form.contractName"
                class="searchInput"
                placeholder="合同名称"
                @keyup.enter="submit"
              />
            </div>
            <button class="searchBtn" type="button" :disabled="loading" @click="submit">
              {{ loading ? '审查中…' : '开始审查' }}
            </button>
            <button class="btn ghost heroReset" type="button" @click="reset">重置</button>
          </div>
          <div class="paramRow">
            <span class="paramLabel">审查模式</span>
            <label class="radioLab"><input v-model="form.reviewMode" type="radio" value="full" />全面</label>
            <label class="radioLab"><input v-model="form.reviewMode" type="radio" value="risk" />风险优先</label>
            <label class="radioLab"><input v-model="form.reviewMode" type="radio" value="compare" />对比修订</label>
            <span class="paramSep" />
            <span class="paramLabel">审查重点</span>
            <input v-model.trim="form.reviewFocus" class="focusInput" type="text" placeholder="审查重点关键词" />
          </div>
        </div>

        <!-- 四卡片：高 / 中 / 低 / 上传 -->
        <section class="topCards" aria-label="风险分级与上传">
          <button
            type="button"
            class="tierCard tierCard--high"
            :class="{ 'tierCard--active': result && tierCardActive('高风险') }"
            @click="setRiskListTier('高风险')"
          >
            <div class="tierCard-head">
              <div class="icon-wrap icon-wrap--high">
                <span class="material-symbols-outlined">gavel</span>
              </div>
              <span class="pill pill--high">高风险</span>
            </div>
            <p class="tierCard-title">{{ riskStats['高风险'] || 0 }}</p>
            <p class="tierCard-desc">权利义务失衡违约金过高等重大不利条款</p>
          </button>
          <button
            type="button"
            class="tierCard tierCard--medium"
            :class="{ 'tierCard--active': result && tierCardActive('中风险') }"
            @click="setRiskListTier('中风险')"
          >
            <div class="tierCard-head">
              <div class="icon-wrap icon-wrap--medium">
                <span class="material-symbols-outlined">balance</span>
              </div>
              <span class="pill pill--medium">中风险</span>
            </div>
            <p class="tierCard-title">{{ riskStats['中风险'] || 0 }}</p>
            <p class="tierCard-desc">表述歧义与责任边界不清等问题</p>
          </button>
          <button
            type="button"
            class="tierCard tierCard--low"
            :class="{ 'tierCard--active': result && tierCardActive('低风险') }"
            @click="setRiskListTier('低风险')"
          >
            <div class="tierCard-head">
              <div class="icon-wrap icon-wrap--low">
                <span class="material-symbols-outlined">edit_note</span>
              </div>
              <span class="pill pill--low">低风险</span>
            </div>
            <p class="tierCard-title">{{ riskStats['低风险'] || 0 }}</p>
            <p class="tierCard-desc">格式措辞与可读性等优化提示</p>
          </button>
          <div
            class="tierCard tierCard--upload"
            :class="{ 'tierCard--drag': uploadDragOver }"
            role="button"
            tabindex="0"
            @click="openFilePicker"
            @keydown.enter.prevent="openFilePicker"
            @keydown.space.prevent="openFilePicker"
            @dragover="onUploadDragOver"
            @dragleave="onUploadDragLeave"
            @drop="onUploadDrop"
          >
            <div class="tierCard-head">
              <div class="icon-wrap icon-wrap--upload">
                <span class="material-symbols-outlined">cloud_upload</span>
              </div>
              <span class="pill pill--uploadLabel">文件上传</span>
            </div>
            <p class="tierCard-title">TXT / DOCX / PDF</p>
            <p class="tierCard-desc">
              {{ selectedFile ? selectedFile.name : '点击或拖拽文件到此处上传' }}
            </p>
            <input
              ref="fileInputRef"
              type="file"
              class="sr-only"
              :accept="acceptTypes"
              @change="onFileChange"
            />
          </div>
        </section>

        <p class="hintBar">
          <button type="button" class="linkBtn" @click="fillSample">填入示例合同</button>
          <span class="hintSep">|</span>
          <span>左侧审查完成后展示带标注的正文；审查前可在此区域下方直接编辑正文。</span>
        </p>

        <!-- 对比修订：Grid 首行左右顶栏同行的单元格自动等高，正文区从第二行起严格对齐 -->
        <div v-if="result && activeTab === 'compare'" class="compareSyncLayout">
          <div class="panel panel-left compareSyncLayout__head compareSyncLayout__headLeft compareSyncLayout__cell">
            <div class="panelHead compareSyncLayout__headInner">
              <h3>
                <span class="material-symbols-outlined sm">article</span>
                合同正文
              </h3>
              <span class="tagMuted">已标注风险位置</span>
            </div>
          </div>
          <div class="panel panel-right compareSyncLayout__head compareSyncLayout__headRight compareSyncLayout__cell">
            <div class="resultTop compareSyncLayout__headInner">
              <div class="resultTitle">
                <span class="material-symbols-outlined sm">compare_arrows</span>
                对比修订
              </div>
              <div class="sort">
                <button :class="{ active: activeTab === 'risks' }" type="button" @click="activeTab = 'risks'">风险清单</button>
                <button :class="{ active: activeTab === 'compare' }" type="button" @click="activeTab = 'compare'">对比修订</button>
              </div>
            </div>
          </div>

          <section class="panel panel-left compareSyncLayout__body compareSyncLayout__bodyLeft compareSyncLayout__cell">
            <div class="compareSyncLayout__scrollPack">
              <div class="annotLegend compareSyncLayout__subRow">
                <span class="lg lg-high">高风险</span>
                <span class="lg lg-med">中风险</span>
                <span class="lg lg-low">低风险</span>
              </div>
              <div
                ref="leftScrollEl"
                class="annotBody compareSyncLayout__mainScroll"
                v-html="annotatedContractHtmlForCompare"
                @scroll="onCompareScroll('left')"
              ></div>
            </div>
            <div v-if="error" class="notice error">{{ error }}</div>
            <div class="panelFoot">
              <button class="btn ghost" type="button" :disabled="loading" @click="reset">清空</button>
              <button class="btn primary" type="button" :disabled="loading" @click="submit">
                {{ loading ? '审查中…' : '扫描全文' }}
              </button>
            </div>
          </section>

          <section class="panel panel-right compareSyncLayout__body compareSyncLayout__bodyRight compareSyncLayout__cell panel-right">
            <div class="compareSyncLayout__scrollPack compareSyncLayout__scrollPack--revised">
              <div class="comparePaneToolbar compareSyncLayout__subRow">
                <span class="comparePaneTitle">修订后全文（对照左侧正文）</span>
                <span v-if="revisedPlainFull && revisions.length" class="revisedInlineLegend">
                  <span class="ril ril-after">修改后</span>
                  <span class="ril ril-before">文中修改前</span>
                </span>
              </div>
              <div
                ref="rightScrollEl"
                class="revisedBodyScroll annotRevised compareSyncLayout__mainScroll"
                @scroll="onCompareScroll('right')"
              >
                <div v-if="revisedPlainFull" class="annotRevisedInner" v-html="annotatedRevisedHtml"></div>
                <div v-else class="annotRevisedPlaceholder">
                  {{ '（暂无可用修订正文，请先完成审查）' }}
                </div>
              </div>
              <div class="compareRevisedFoot">
                <button class="btn ghost" type="button" :disabled="!revisedPlainFull" @click="copyRevised">
                  复制修订版全文
                </button>
              </div>
            </div>

            <details v-if="revisedTextAppendix" class="revisedAppendix">
              <summary>审查打包原文（附录）</summary>
              <pre class="revisedAppendixPre">{{ revisedTextAppendix }}</pre>
            </details>
          </section>
        </div>

        <!-- 主区：风险清单 / 未审查 / 未出结果时的对比占位 -->
        <div v-else class="mainGrid" :class="{ 'mainGrid--riskListFlow': !!result }">
          <section class="panel panel-left">
            <div class="panelHead">
              <h3>
                <span class="material-symbols-outlined sm">article</span>
                合同正文
              </h3>
              <span v-if="result" class="tagMuted">已标注风险位置</span>
            </div>
            <div v-if="!result" class="bodyWrap">
              <textarea
                v-model.trim="form.rawText"
                class="rawTextarea"
                rows="18"
                placeholder="粘贴合同全文，或通过上方卡片上传 TXT / DOCX / PDF 后在此核对或补充。"
              />
            </div>
            <div v-else class="bodyWrap bodyWrap--annotated">
              <div class="annotLegend">
                <span class="lg lg-high">高风险</span>
                <span class="lg lg-med">中风险</span>
                <span class="lg lg-low">低风险</span>
              </div>
              <div
                ref="leftScrollEl"
                class="annotBody"
                v-html="annotatedContractHtmlForRiskList"
              ></div>
            </div>
            <div v-if="error" class="notice error">{{ error }}</div>
            <div class="panelFoot">
              <button class="btn ghost" type="button" :disabled="loading" @click="reset">清空</button>
              <button class="btn primary" type="button" :disabled="loading" @click="submit">
                {{ loading ? '审查中…' : '扫描全文' }}
              </button>
            </div>
          </section>

          <section class="panel panel-right">
            <div class="resultTop">
              <div class="resultTitle">
                <span class="material-symbols-outlined sm">policy</span>
                风险审查
                <template v-if="result && activeTab === 'risks'">
                  <span v-if="riskListLevelFilter" class="filterChip">{{ riskListLevelFilter }}</span>
                  <button
                    v-if="riskListLevelFilter"
                    type="button"
                    class="linkBtn tiny"
                    @click="showAllRisksInList"
                  >
                    展示全部
                  </button>
                  <button
                    v-else
                    type="button"
                    class="linkBtn tiny"
                    @click="setRiskListTier('高风险')"
                  >
                    只看高风险
                  </button>
                </template>
              </div>
              <div class="sort">
                <button :class="{ active: activeTab === 'risks' }" type="button" @click="activeTab = 'risks'">风险清单</button>
                <button :class="{ active: activeTab === 'compare' }" type="button" @click="activeTab = 'compare'">对比修订</button>
              </div>
            </div>

            <div v-if="result && activeTab === 'risks'" class="summaryStrip">
              <span class="badge" :class="levelClass(result.overallRisk)">{{ result.overallRisk }}</span>
              <strong>{{ result.contractName || form.contractName || '审查摘要' }}</strong>
              <p>{{ result.summary }}</p>
            </div>

            <div class="bodyWrap bodyWrap--rightMirror">
              <template v-if="activeTab === 'risks'">
                <div v-if="!risks.length" class="empty empty--mirrorTextarea">
                  {{ result ? '本次审查未返回风险条目。' : '请先粘贴正文或上传文件并开始审查。' }}
                </div>
                <div
                  v-else-if="!filteredRiskRows.length"
                  class="empty empty--mirrorTextarea empty--filtered"
                >
                  当前档位暂无风险条目，可切换顶部档位卡片，或点击「展示全部」。
                </div>
                <article
                  v-for="{ item, originalIndex } in filteredRiskRows"
                  :key="`${originalIndex}-${item.location}-${item.clauseTitle}`"
                  class="riskCard riskCard--interactive"
                  :class="levelClass(item.level)"
                  role="button"
                  tabindex="0"
                  @click="setRiskListTier(item.level)"
                  @keydown.enter.prevent="setRiskListTier(item.level)"
                  @keydown.space.prevent="setRiskListTier(item.level)"
                >
                  <div class="riskCardBody">
                    <div class="mini">
                      <span class="badge" :class="levelClass(item.level)">{{ item.level }}</span>
                      <span class="miniText">{{ item.location }}</span>
                    </div>
                    <h4 class="clauseTitle">{{ item.clauseTitle }}</h4>
                    <div class="block">
                      <h5>原条款</h5>
                      <p class="clausePlain">{{ item.originalClause }}</p>
                    </div>
                    <div class="subCard">
                      <h5>审查意见</h5>
                      <p class="clausePlain">{{ item.issue }}</p>
                      <p class="basis clausePlain">{{ item.legalBasis }}</p>
                    </div>
                    <div class="compareBox">
                      <div>
                        <h5>修改建议</h5>
                        <p class="clausePlain">{{ item.suggestion }}</p>
                      </div>
                      <div>
                        <h5>建议条款</h5>
                        <p class="clausePlain">{{ item.revisedClause }}</p>
                      </div>
                    </div>
                    <div v-if="revisions[originalIndex]" class="revisionPair">
                      <h5 class="revisionPairTitle">本条修订前后（对照）</h5>
                      <div class="compareBox compareBox--stack">
                        <div>
                          <h5>修改前</h5>
                          <p class="clausePlain">{{ revisions[originalIndex].beforeText }}</p>
                        </div>
                        <div>
                          <h5>修改后</h5>
                          <p class="clausePlain">{{ revisions[originalIndex].afterText }}</p>
                        </div>
                      </div>
                    </div>
                    <div class="tags">
                      <span v-for="tag in item.tags || []" :key="tag">{{ tag }}</span>
                    </div>
                  </div>
                </article>
              </template>

              <template v-else>
                <div class="comparePane comparePane--placeholder">
                  <div class="comparePaneToolbar">
                    <span class="comparePaneTitle">修订后全文</span>
                    <button class="btn ghost" type="button" disabled>复制修订版全文</button>
                  </div>
                  <div class="revisedBodyScroll annotRevised revisedBodyScroll--mirror">
                    <div v-if="revisedPlainFull" class="annotRevisedInner" v-html="annotatedRevisedHtml"></div>
                    <div v-else class="annotRevisedPlaceholder">请先完成审查后再查看修订对照。</div>
                  </div>
                </div>
              </template>
            </div>

            <div v-if="result?.checklist?.length" class="checklist">
              <h5><span class="material-symbols-outlined sm">fact_check</span> 签署前核对</h5>
              <div class="checkTags">
                <span v-for="c in result.checklist" :key="c">{{ c }}</span>
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
  padding: 28px 32px;
  margin-bottom: 24px;
  box-shadow: var(--shadow-sm);
}
.heroInner {
  text-align: center;
  max-width: 900px;
  margin: 0 auto;
}
.h1 {
  margin: 0 0 8px;
  font-size: 36px;
  font-weight: 700;
  letter-spacing: -0.02em;
  line-height: 1.2;
  color: var(--on-surface);
}
.sub {
  margin: 0;
  font-size: 16px;
  line-height: 1.65;
  color: var(--on-surface-variant);
}
.heroRow {
  margin-top: 20px;
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  justify-content: center;
}
.typeSelect {
  width: 160px;
  height: 48px;
  border: 1px solid var(--outline-variant);
  border-radius: 8px;
  background: var(--surface-container-lowest);
  color: var(--on-surface);
  padding: 0 10px;
  font: inherit;
}
.searchBox {
  flex: 1;
  min-width: 200px;
  max-width: 420px;
  position: relative;
  display: flex;
  align-items: center;
  background: var(--surface-bright);
  border: 1px solid var(--outline-variant);
  border-radius: 8px;
}
.searchIcon {
  position: absolute;
  left: 12px;
  color: var(--outline);
  font-size: 22px;
}
.searchInput {
  width: 100%;
  height: 48px;
  border: none;
  outline: none;
  background: transparent;
  padding: 0 12px 0 44px;
  font-size: 15px;
  color: var(--on-surface);
}
.searchBtn {
  height: 48px;
  padding: 0 22px;
  border: none;
  border-radius: 8px;
  background: var(--primary-container);
  color: var(--on-primary);
  font-weight: 600;
  cursor: pointer;
  box-shadow: var(--shadow-sm);
}
.searchBtn:disabled {
  opacity: 0.65;
  cursor: wait;
}
.heroReset {
  height: 48px;
}
.paramRow {
  margin-top: 16px;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: center;
  gap: 10px 14px;
  font-size: 14px;
  color: var(--on-surface);
}
.paramLabel {
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.05em;
  text-transform: uppercase;
  color: var(--on-surface-variant);
}
.radioLab {
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
}
.paramSep {
  width: 1px;
  height: 20px;
  background: var(--surface-variant);
}
.focusInput {
  flex: 1;
  min-width: 200px;
  max-width: 480px;
  height: 40px;
  border: 1px solid var(--outline-variant);
  border-radius: 6px;
  padding: 0 10px;
  font: inherit;
  background: var(--surface-container-lowest);
  color: var(--on-surface);
}

.topCards {
  display: grid;
  grid-template-columns: 1fr;
  gap: 16px;
  margin-bottom: 12px;
}
@media (min-width: 720px) {
  .topCards {
    grid-template-columns: repeat(2, 1fr);
  }
}
@media (min-width: 1100px) {
  .topCards {
    grid-template-columns: repeat(4, 1fr);
  }
}

.tierCard {
  position: relative;
  text-align: left;
  border: 1px solid var(--surface-container-highest);
  border-radius: var(--radius-lg, 12px);
  background: var(--surface-container-lowest);
  padding: 20px;
  box-shadow: var(--shadow-sm);
  cursor: pointer;
  transition:
    box-shadow 0.2s,
    border-color 0.2s,
    transform 0.15s;
  font: inherit;
  color: inherit;
}
.tierCard:hover {
  box-shadow: var(--shadow-md);
}
.tierCard--active {
  border-color: var(--primary-container);
  box-shadow: 0 0 0 1px color-mix(in srgb, var(--primary-container) 40%, transparent);
}
.tierCard--upload {
  cursor: pointer;
}
.tierCard--drag {
  border-color: var(--primary-container);
  background: color-mix(in srgb, var(--primary-fixed) 35%, var(--surface-container-lowest));
}
.tierCard::before {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 4px;
  border-radius: var(--radius-lg, 12px) 0 0 var(--radius-lg, 12px);
}
.tierCard--high::before {
  background: #ba1a1a;
}
.tierCard--medium::before {
  background: #b26a00;
}
.tierCard--low::before {
  background: #4e6b20;
}
.tierCard--upload::before {
  background: var(--primary-container);
}
.tierCard-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 10px;
}
.icon-wrap {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  border-radius: 10px;
}
.icon-wrap--high {
  background: #ffdad6;
  color: #93000a;
}
.icon-wrap--medium {
  background: #ffddb0;
  color: #6f3b00;
}
.icon-wrap--low {
  background: #d7e8b5;
  color: #223600;
}
.icon-wrap--upload {
  background: var(--tertiary-fixed);
  color: var(--tertiary);
}
.pill {
  flex-shrink: 0;
  font-size: 13px;
  font-weight: 800;
  letter-spacing: 0.06em;
  padding: 6px 11px;
  border-radius: 8px;
  border: 1px solid transparent;
  line-height: 1.2;
}
.pill--high {
  background: #ffdad6;
  color: #93000a;
  border-color: color-mix(in srgb, #ba1a1a 32%, transparent);
}
.pill--medium {
  background: #ffddb0;
  color: #6f3b00;
  border-color: color-mix(in srgb, #b26a00 32%, transparent);
}
.pill--low {
  background: #d7e8b5;
  color: #223600;
  border-color: color-mix(in srgb, #4e6b20 32%, transparent);
}
.pill--uploadLabel {
  background: color-mix(in srgb, var(--tertiary-fixed) 88%, white);
  color: var(--on-tertiary-fixed);
  border-color: color-mix(in srgb, var(--tertiary) 28%, transparent);
}
.tierCard-title {
  margin: 0 0 8px;
  font-size: 28px;
  font-weight: 700;
  color: var(--on-surface);
  line-height: 1.15;
}
.tierCard-desc {
  margin: 0;
  font-size: 12px;
  line-height: 1.45;
  color: var(--on-surface-variant);
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 1;
  overflow: hidden;
  word-break: break-word;
}

.hintBar {
  margin: 0 0 20px;
  font-size: 13px;
  color: var(--on-surface-variant);
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.hintSep {
  color: var(--outline);
}
.linkBtn {
  border: none;
  background: none;
  padding: 0;
  color: var(--primary-container);
  font-weight: 600;
  cursor: pointer;
  text-decoration: underline;
  font-size: inherit;
}
.linkBtn.tiny {
  font-size: 12px;
  margin-left: 8px;
}

.mainGrid {
  display: grid;
  grid-template-columns: 1fr;
  gap: 24px;
  align-items: stretch;
}
@media (min-width: 1024px) {
  .mainGrid {
    grid-template-columns: minmax(0, 11fr) minmax(0, 13fr);
    align-items: stretch;
  }
}

/* 风险清单：左右正文与清单随页面滚动，不在面板内单独滚动 */
.mainGrid--riskListFlow {
  align-items: start;
}
.mainGrid--riskListFlow .panel-left,
.mainGrid--riskListFlow .panel-right {
  overflow: visible;
  min-height: 0;
}
.mainGrid--riskListFlow .panel-right.panel {
  overflow: visible;
}
.mainGrid--riskListFlow .bodyWrap,
.mainGrid--riskListFlow .bodyWrap--annotated {
  flex: none;
  min-height: 0;
}
.mainGrid--riskListFlow .annotBody {
  flex: none;
  min-height: 0;
  max-height: none;
  overflow: visible;
}
.mainGrid--riskListFlow .bodyWrap--rightMirror {
  flex: none;
  min-height: 0;
  overflow: visible;
}
.mainGrid--riskListFlow .bodyWrap--rightMirror .empty.empty--mirrorTextarea {
  min-height: 160px;
}
.mainGrid--riskListFlow .bodyWrap--rightMirror .empty.empty--filtered {
  min-height: 120px;
}

/* 未出审查结果时：左右正文区同顶线、占位与左侧输入框同高 */
.mainGrid .panel-left,
.mainGrid .panel-right {
  display: flex;
  flex-direction: column;
  min-height: 0;
}
@media (min-width: 1024px) {
  .mainGrid .panelHead,
  .mainGrid .resultTop {
    min-height: 72px;
    box-sizing: border-box;
  }
}
.mainGrid .panelHead {
  padding: 14px 18px;
}
.mainGrid .bodyWrap--rightMirror {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 16px 18px;
}
.mainGrid .bodyWrap--rightMirror .empty.empty--mirrorTextarea {
  flex: 1;
  min-height: 360px;
  margin: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  text-align: center;
  box-sizing: border-box;
}
.mainGrid .bodyWrap--rightMirror .riskCard {
  margin-left: 0;
  margin-right: 0;
}
.mainGrid .bodyWrap--rightMirror .comparePane--placeholder {
  flex: 1;
  min-height: 360px;
}
.mainGrid .revisedBodyScroll--mirror {
  flex: 1;
  margin: 0;
  min-height: 200px;
}

/* 对比修订：上下两块卡片接缝；左右列之间留白与风险清单 mainGrid 一致 */
.compareSyncLayout {
  display: grid;
  row-gap: 0;
  column-gap: 0;
  align-items: stretch;
  grid-template-columns: 1fr;
  grid-template-areas:
    'head-l'
    'body-l'
    'head-r'
    'body-r';
}
@media (min-width: 1024px) {
  .compareSyncLayout {
    grid-template-columns: minmax(0, 11fr) minmax(0, 13fr);
    grid-template-rows: auto minmax(420px, 1fr);
    grid-template-areas:
      'head-l head-r'
      'body-l body-r';
    column-gap: 24px;
  }
  /* 顶栏与正文外框连成一块；左右栏独立卡片，中间为页面底色留白 */
  .compareSyncLayout__headLeft.panel {
    border-radius: var(--radius-xl, 12px) var(--radius-xl, 12px) 0 0;
    border-bottom: none;
  }
  .compareSyncLayout__headRight.panel {
    border-radius: var(--radius-xl, 12px) var(--radius-xl, 12px) 0 0;
    border-bottom: none;
  }
  .compareSyncLayout__bodyLeft.panel {
    border-radius: 0 0 var(--radius-xl, 12px) var(--radius-xl, 12px);
    border-top: none;
  }
  .compareSyncLayout__bodyRight.panel {
    border-radius: 0 0 var(--radius-xl, 12px) var(--radius-xl, 12px);
    border-top: none;
  }
  /* 与风险清单顶栏同高，保证左右横线对齐 */
  .compareSyncLayout__head .compareSyncLayout__headInner.panelHead,
  .compareSyncLayout__head .compareSyncLayout__headInner.resultTop {
    min-height: 72px;
  }
}
.compareSyncLayout__headLeft {
  grid-area: head-l;
}
.compareSyncLayout__headRight {
  grid-area: head-r;
}
.compareSyncLayout__bodyLeft {
  grid-area: body-l;
}
.compareSyncLayout__bodyRight {
  grid-area: body-r;
}
.compareSyncLayout__cell {
  min-width: 0;
}
.compareSyncLayout__head {
  padding: 0;
}
.compareSyncLayout__head .compareSyncLayout__headInner.panelHead {
  margin: 0;
  border-radius: 0;
  border: none;
  /* 与正文区之间仅保留极淡分界 */
  border-bottom: 1px solid color-mix(in srgb, var(--outline-variant) 45%, var(--surface-container-lowest));
  background: var(--surface-container-lowest);
  padding: 14px 18px;
  min-height: 58px;
  box-sizing: border-box;
  justify-content: flex-start;
  align-items: center;
  gap: 12px;
}
.compareSyncLayout__head .compareSyncLayout__headInner.panelHead .tagMuted {
  margin-left: auto;
}
.compareSyncLayout__head .compareSyncLayout__headInner.resultTop {
  margin: 0;
  border-radius: 0;
  border-bottom: 1px solid color-mix(in srgb, var(--outline-variant) 45%, var(--surface-container-lowest));
  background: var(--surface-container-lowest);
  min-height: 58px;
  box-sizing: border-box;
  justify-content: flex-start;
  align-items: center;
  gap: 12px;
}
.compareSyncLayout__head .compareSyncLayout__headInner.resultTop .sort {
  margin-left: auto;
}
.compareSyncLayout__body {
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.compareSyncLayout__scrollPack {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 10px 18px 16px;
}
.compareSyncLayout__scrollPack--revised {
  padding-bottom: 12px;
}
.compareSyncLayout__subRow {
  flex-shrink: 0;
  margin-bottom: 12px;
  min-height: 40px;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px 12px;
  box-sizing: border-box;
}
.compareSyncLayout .annotLegend {
  margin-bottom: 0;
}
.compareSyncLayout .comparePaneToolbar {
  padding: 0;
  margin-bottom: 0;
  border: none;
  justify-content: flex-start;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 12px;
  width: 100%;
}
.compareRevisedFoot {
  flex-shrink: 0;
  display: flex;
  justify-content: flex-end;
  align-items: center;
  padding-top: 10px;
  margin-top: 2px;
  border-top: 1px solid var(--surface-variant);
}
.compareRevisedFoot .btn {
  flex-shrink: 0;
}
.compareSyncLayout__mainScroll {
  flex: 1 1 auto;
  min-height: 0;
  max-height: min(70vh, 720px);
  overflow: auto;
}
.compareSyncLayout .annotBody {
  max-height: none;
}
.compareSyncLayout .revisedBodyScroll {
  margin: 0;
  max-height: none;
}
.comparePane--placeholder {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.comparePane--placeholder .revisedBodyScroll {
  flex: 1;
  min-height: 120px;
}

.panel {
  border-radius: var(--radius-xl, 12px);
  border: 1px solid var(--surface-container-highest);
  background: var(--surface-container-lowest);
  box-shadow: var(--shadow-sm);
  min-height: 0;
}
.panel-left {
  display: flex;
  flex-direction: column;
}
.panelHead {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 18px;
  border-bottom: 1px solid var(--surface-container-highest);
}
.panelHead h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--on-surface);
  display: flex;
  align-items: center;
  gap: 8px;
}
.sm {
  font-size: 20px;
  color: var(--primary-container);
}
.tagMuted {
  font-size: 12px;
  color: var(--on-surface-variant);
}
.bodyWrap {
  padding: 16px 18px;
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.bodyWrap:not(.bodyWrap--annotated) {
  min-height: 280px;
}
.bodyWrap--annotated {
  flex: 1;
}
.rawTextarea {
  width: 100%;
  min-height: 360px;
  box-sizing: border-box;
  border: 1px solid var(--outline-variant);
  border-radius: 8px;
  padding: 14px;
  font: inherit;
  line-height: 1.75;
  resize: vertical;
  background: var(--surface-bright);
  color: var(--on-surface);
}
.annotLegend {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 12px;
  font-size: 12px;
}
.lg {
  padding: 4px 10px;
  border-radius: 6px;
  font-weight: 600;
}
.lg-high {
  background: #ffdad6;
  color: #93000a;
}
.lg-med {
  background: #ffddb0;
  color: #6f3b00;
}
.lg-low {
  background: #d7e8b5;
  color: #223600;
}
.annotBody {
  flex: 1;
  min-height: 0;
  font-size: 15px;
  line-height: 1.85;
  color: var(--on-surface);
  text-align: justify;
  text-justify: inter-ideograph;
  word-break: break-word;
  max-height: min(70vh, 720px);
  overflow: auto;
  padding: 14px;
  border-radius: 8px;
  border: 1px solid var(--surface-variant);
  background: var(--surface-bright);
}
.annotBody :deep(.rm) {
  text-decoration: underline;
  text-decoration-thickness: 2px;
  text-underline-offset: 3px;
  cursor: help;
}
.annotBody :deep(.rm-high) {
  text-decoration-color: #ba1a1a;
  background: color-mix(in srgb, #ffdad6 55%, transparent);
}
.annotBody :deep(.rm-med) {
  text-decoration-color: #b26a00;
  background: color-mix(in srgb, #ffddb0 50%, transparent);
}
.annotBody :deep(.rm-low) {
  text-decoration-color: #4e6b20;
  background: color-mix(in srgb, #d7e8b5 45%, transparent);
}
.annotBody :deep(.rm-label) {
  display: inline-block;
  margin-right: 4px;
  font-size: 11px;
  font-weight: 800;
  vertical-align: super;
  line-height: 1;
  padding: 1px 5px;
  border-radius: 4px;
  text-decoration: none;
}
.annotBody :deep(.rm-high .rm-label) {
  background: #93000a;
  color: #fff;
}
.annotBody :deep(.rm-med .rm-label) {
  background: #6f3b00;
  color: #fff;
}
.annotBody :deep(.rm-low .rm-label) {
  background: #223600;
  color: #fff;
}

.annotRevised {
  cursor: text;
  /* 与左侧 annotBody 一致：换行仅由 v-html 内 <br /> 控制，禁止 pre-wrap 与正文内换行叠加 */
  white-space: normal;
}
.annotRevisedInner {
  min-height: 1em;
  white-space: normal;
}
.annotRevisedPlaceholder {
  color: var(--secondary);
  text-align: center;
  padding: 24px 12px;
}
.annotRevised :deep(.rm-revised) {
  display: inline;
  text-decoration: underline;
  text-decoration-color: #0f9f6e;
  text-decoration-thickness: 2px;
  text-underline-offset: 3px;
  background: color-mix(in srgb, #b9f6ca 42%, transparent);
  cursor: help;
}
.annotRevised :deep(.rm-revised-before) {
  display: inline;
  text-decoration: underline;
  text-decoration-color: #c67d00;
  text-decoration-thickness: 2px;
  text-underline-offset: 3px;
  background: color-mix(in srgb, #ffe0b2 45%, transparent);
  cursor: help;
}
.clausePlain {
  white-space: pre-wrap;
  word-break: break-word;
}
.revisionPair {
  margin-bottom: 12px;
  padding-top: 10px;
  border-top: 1px dashed var(--outline-variant, var(--surface-variant));
}
.revisionPairTitle {
  margin: 0 0 8px;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  color: var(--secondary);
}
.compareBox--stack {
  grid-template-columns: 1fr !important;
}

.notice {
  margin: 0 18px 12px;
  border-radius: 8px;
  padding: 12px;
}
.notice.error {
  color: var(--on-error-container);
  background: var(--error-container);
}
.panelFoot {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding: 14px 18px 18px;
  border-top: 1px solid var(--surface-container-highest);
}
.btn {
  height: 40px;
  padding: 0 16px;
  border-radius: 6px;
  font-weight: 600;
  cursor: pointer;
  border: 1px solid transparent;
  font: inherit;
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
.btn.primary {
  background: var(--primary-container);
  color: var(--on-primary);
  box-shadow: var(--shadow-sm);
}

.panel-right {
  padding: 0;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  min-height: 400px;
}
.compareSyncLayout .panel-right {
  min-height: 0;
}
.resultTop {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  padding: 14px 18px;
  border-bottom: 1px solid var(--surface-container-highest);
  background: var(--surface-container-low);
}
.resultTitle {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
  color: var(--on-surface);
}
.filterChip {
  font-size: 12px;
  padding: 2px 8px;
  border-radius: 999px;
  background: var(--primary-fixed);
  color: var(--on-primary-fixed);
}
.sort {
  display: flex;
  gap: 8px;
}
.sort button {
  height: 34px;
  padding: 0 12px;
  border: 1px solid var(--outline-variant);
  border-radius: 6px;
  background: var(--surface-container-lowest);
  color: var(--secondary);
  cursor: pointer;
  font: inherit;
}
.sort button.active {
  border-color: var(--primary-container);
  color: var(--primary-container);
  background: var(--primary-fixed);
}
.summaryStrip {
  padding: 14px 18px;
  border-bottom: 1px solid var(--surface-container-highest);
  font-size: 14px;
  color: var(--on-surface-variant);
  line-height: 1.6;
}
.summaryStrip strong {
  display: block;
  margin: 6px 0 4px;
  font-size: 17px;
  color: var(--on-surface);
}
.summaryStrip p {
  margin: 0;
}
.badge {
  font-size: 11px;
  font-weight: 800;
  padding: 4px 8px;
  border-radius: 6px;
  display: inline-block;
}
.badge.high {
  background: #ffdad6;
  color: #93000a;
}
.badge.medium {
  background: #ffddb0;
  color: #6f3b00;
}
.badge.low {
  background: #d7e8b5;
  color: #223600;
}

.empty {
  margin: 16px 18px;
  padding: 18px;
  border-radius: 8px;
  border: 1px dashed var(--outline-variant);
  color: var(--secondary);
  font-size: 14px;
}
.riskCard--interactive {
  cursor: pointer;
}
.riskCard--interactive:focus-visible {
  outline: 2px solid var(--primary-container);
  outline-offset: 2px;
}
.riskCard {
  margin: 12px 18px;
  border: 1px solid var(--surface-variant);
  border-radius: 10px;
  overflow: hidden;
  box-shadow: var(--shadow-sm);
  background: var(--surface-container-lowest);
}
.riskCard.high {
  border-left: 4px solid #ba1a1a;
}
.riskCard.medium {
  border-left: 4px solid #b26a00;
}
.riskCard.low {
  border-left: 4px solid #4e6b20;
}
.riskCardBody {
  padding: 16px;
}
.mini {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.miniText {
  font-size: 13px;
  color: var(--secondary);
}
.clauseTitle {
  margin: 0 0 12px;
  font-size: 18px;
  color: var(--on-surface);
  line-height: 1.35;
}
.block h5,
.subCard h5,
.compareBox h5,
.checklist h5 {
  margin: 0 0 6px;
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  color: var(--secondary);
  display: flex;
  align-items: center;
  gap: 6px;
}
.block p,
.subCard p {
  margin: 0;
  line-height: 1.75;
  color: var(--on-surface);
}
.block {
  margin-bottom: 12px;
}
.subCard {
  background: var(--surface-bright);
  border: 1px solid var(--primary-fixed);
  border-radius: 8px;
  padding: 12px;
  margin-bottom: 12px;
}
.subCard .basis {
  margin-top: 8px;
  font-size: 13px;
  color: var(--secondary);
}
.compareBox {
  display: grid;
  grid-template-columns: 1fr;
  gap: 10px;
  margin-bottom: 12px;
}
@media (min-width: 640px) {
  .compareBox {
    grid-template-columns: 1fr 1fr;
  }
}
.compareBox div {
  border: 1px solid var(--surface-variant);
  border-radius: 8px;
  padding: 10px;
  background: var(--surface-container-low);
}
.compareBox p {
  margin: 0;
  font-size: 14px;
  color: var(--on-surface-variant);
  line-height: 1.65;
}
.tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.tags span {
  font-size: 12px;
  padding: 4px 8px;
  border-radius: 999px;
  background: var(--surface-container);
  border: 1px solid var(--surface-variant);
  color: var(--on-surface-variant);
}

.compareHeader {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 18px;
  font-weight: 600;
  color: var(--on-surface);
}

/* 对比修订：主正文区与左侧合同滚动区同高、同排版，便于左右核对 */
.comparePane {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
  padding-top: 4px;
}
.comparePaneToolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
  padding: 4px 18px 12px;
  margin-bottom: 0;
  border-bottom: 1px solid transparent;
}
.revisedInlineLegend {
  display: inline-flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  font-size: 12px;
  color: var(--secondary);
}
.revisedInlineLegend .ril {
  text-decoration: underline;
  text-underline-offset: 3px;
  text-decoration-thickness: 2px;
}
.revisedInlineLegend .ril-after {
  text-decoration-color: #0f9f6e;
}
.revisedInlineLegend .ril-before {
  text-decoration-color: #c67d00;
}
.comparePaneTitle {
  font-weight: 600;
  font-size: 15px;
  color: var(--on-surface);
}
.revisedBodyScroll {
  flex: 1;
  min-height: 200px;
  max-height: min(70vh, 720px);
  overflow: auto;
  margin: 0 18px;
  padding: 14px;
  border-radius: 8px;
  border: 1px solid var(--surface-variant);
  background: var(--surface-bright);
  font-family: inherit;
  font-size: 15px;
  line-height: 1.85;
  color: var(--on-surface);
  text-align: justify;
  text-justify: inter-ideograph;
  word-break: break-word;
  white-space: pre-wrap;
}
/* v-html 修订正文：与 annotBody 一致用 normal，避免与 <br /> 双重换行 */
.revisedBodyScroll.annotRevised {
  white-space: normal;
}
.revisedAppendix {
  margin: 16px 18px 0;
  padding: 0;
  font-size: 13px;
  color: var(--on-surface-variant);
}
.revisedAppendix summary {
  cursor: pointer;
  font-weight: 600;
  color: var(--secondary);
  padding: 8px 0;
}
.revisedAppendixPre {
  margin: 8px 0 0;
  padding: 12px;
  border-radius: 8px;
  border: 1px dashed var(--outline-variant);
  background: var(--surface-container-low);
  white-space: pre-wrap;
  font: inherit;
  line-height: 1.65;
  max-height: 280px;
  overflow: auto;
}

.checklist {
  padding: 14px 18px 20px;
  border-top: 1px solid var(--surface-container-highest);
}
.checkTags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 8px;
}
.checkTags span {
  font-size: 12px;
  padding: 6px 10px;
  border-radius: 999px;
  background: var(--surface-container);
  border: 1px solid var(--surface-variant);
  color: var(--on-surface-variant);
}

@media (max-width: 640px) {
  .wrap {
    padding: 16px;
  }
  .h1 {
    font-size: 28px;
  }
  .hero {
    padding: 20px;
  }
}
</style>
