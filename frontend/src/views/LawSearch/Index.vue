<script setup>
import AppLayout from '../../components/common/AppLayout.vue'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { getLawMeta, searchLaws, syncLaws } from '../../api/law'
import { LAW_SEARCH_RESTORE_FLAG, LAW_SEARCH_SNAPSHOT_KEY } from '../../constants/lawSearchSession.js'
import {
  ISSUING_BODY_TREE_DATA,
  LAW_CATEGORY_TREE_DATA,
  TIMELINESS_OPTIONS,
  flattenFacetTree,
} from '../../constants/lawFacetTrees.js'
import {
  CONTENT_EXCERPT_LIST_MAX,
  excerptLawContent,
  formatLawDate,
  interpretationBodyToHtml,
  interpretationPlainForCopy,
  splitLeadingArticleHeading,
  isHiddenSourceUrl,
  isNpcFlkNationalDatabase,
  LAW_EXCEPTION_EMPTY,
  LAW_INTERPRETATION_EMPTY,
  stripBundledInterpretationSections,
  stripNpcInterpretationDisclaimer,
  stripTechnicalNoiseFromInterpretation,
} from '../../utils/lawDisplay'

const router = useRouter()
const loading = ref(false)
const syncing = ref(false)
const error = ref('')
const syncMessage = ref('')
const sourceUrl = ref('')

const timelinessOptions = TIMELINESS_OPTIONS

const categoryOptions = computed(() => {
  const base = flattenFacetTree(LAW_CATEGORY_TREE_DATA)
  const seen = new Set(base.map((o) => o.value))
  const extra = (meta.value.categories || [])
    .filter((c) => c && !seen.has(c))
    .map((c) => {
      seen.add(c)
      return { label: c, value: c }
    })
  return [...base, ...extra]
})

const issuingBodyOptions = computed(() => {
  const base = flattenFacetTree(ISSUING_BODY_TREE_DATA)
  const seen = new Set(base.map((o) => o.value))
  const extra = (meta.value.issuingBodies || [])
    .filter((c) => c && !seen.has(c))
    .map((c) => {
      seen.add(c)
      return { label: c, value: c }
    })
  return [...base, ...extra]
})

const NPC_SEARCH_FALLBACK = 'https://flk.npc.gov.cn/search'

const meta = ref({
  categories: [],
  issuingBodies: [],
  total: 0,
  vectorEnabled: false,
  embeddingModel: 'text-embedding-v4',
  lastSyncAt: null,
  npcPublicSearchUrl: NPC_SEARCH_FALLBACK,
})
const response = ref({ total: 0, items: [], vectorEnabled: false, lastSyncAt: null })
const hasSearched = ref(false)
const form = reactive({
  q: '',
  searchType: 'fullText',
  matchMode: 'fuzzy',
  keywordMode: 'any',
  categories: [],
  statuses: [],
  issuingBodies: [],
  publishDateFrom: '',
  publishDateTo: '',
  effectiveDateFrom: '',
  effectiveDateTo: '',
  sort: 'relevance',
  page: 1,
  size: 10,
})

const npcSearchUrl = computed(() => meta.value.npcPublicSearchUrl || NPC_SEARCH_FALLBACK)

const pageSizeOptions = [10]

const totalPages = computed(() => {
  const t = response.value.total || 0
  const ps = form.size > 0 ? form.size : 10
  return t === 0 ? 1 : Math.ceil(t / ps)
})

const searchTypeLabel = computed(() => {
  const map = { fullText: '全文检索', cause: '案由检索', articleNo: '条文编号' }
  return map[form.searchType] || '全文检索'
})

/** 公布、施行各选一个日期区间，映射到后端 publishDateFrom/To、effectiveDateFrom/To */
const publishDateRange = ref(null)
const effectiveDateRange = ref(null)

function syncDateRangesToForm() {
  const p = publishDateRange.value
  if (Array.isArray(p) && p.length === 2) {
    form.publishDateFrom = p[0] || ''
    form.publishDateTo = p[1] || ''
  } else {
    form.publishDateFrom = ''
    form.publishDateTo = ''
  }
  const e = effectiveDateRange.value
  if (Array.isArray(e) && e.length === 2) {
    form.effectiveDateFrom = e[0] || ''
    form.effectiveDateTo = e[1] || ''
  } else {
    form.effectiveDateFrom = ''
    form.effectiveDateTo = ''
  }
}

const publishPopVisible = ref(false)
const publishStep = ref(0)
const publishDraftStart = ref('')
const publishDraftEnd = ref('')

const effectivePopVisible = ref(false)
const effectiveStep = ref(0)
const effectiveDraftStart = ref('')
const effectiveDraftEnd = ref('')

const publishInputGuide =
  '点日历：先选公布起始日 → 再选截止日（闭区间，可选）'

const effectiveInputGuide =
  '点日历：先选施行起始日 → 再选截止日（闭区间，可选）'

const publishFieldDisplay = computed(() => {
  const r = publishDateRange.value
  if (Array.isArray(r) && r[0] && r[1]) {
    return `已选公布区间：${r[0]} 至 ${r[1]}（含首尾日）· 点击可重选`
  }
  return ''
})

const effectiveFieldDisplay = computed(() => {
  const r = effectiveDateRange.value
  if (Array.isArray(r) && r[0] && r[1]) {
    return `已选施行区间：${r[0]} 至 ${r[1]}（含首尾日）· 点击可重选`
  }
  return ''
})

const publishRangeTitle = computed(() => {
  const r = publishDateRange.value
  if (Array.isArray(r) && r[0] && r[1]) {
    return `检索将限定公布日期在 ${r[0]} 至 ${r[1]} 之间（含起止日）。点击输入框可重新选择；弹层内可「清除区间」。`
  }
  return publishInputGuide
})

const effectiveRangeTitle = computed(() => {
  const r = effectiveDateRange.value
  if (Array.isArray(r) && r[0] && r[1]) {
    return `检索将限定施行日期在 ${r[0]} 至 ${r[1]} 之间（含起止日）。点击输入框可重新选择；弹层内可「清除区间」。`
  }
  return effectiveInputGuide
})

function parseYmd(d) {
  if (d instanceof Date) {
    return new Date(d.getFullYear(), d.getMonth(), d.getDate())
  }
  if (typeof d === 'string' && /^\d{4}-\d{2}-\d{2}/.test(d)) {
    const [y, m, day] = d.slice(0, 10).split('-').map(Number)
    return new Date(y, m - 1, day)
  }
  const x = new Date(d)
  if (Number.isNaN(x.getTime())) return null
  return new Date(x.getFullYear(), x.getMonth(), x.getDate())
}

function disabledEndBeforeStart(startStr) {
  const s0 = startStr ? parseYmd(startStr) : null
  if (!s0) return () => false
  return (date) => {
    const t = parseYmd(date)
    return t != null && t < s0
  }
}

function onPublishPopVisible(open) {
  if (open) {
    const r = publishDateRange.value
    if (Array.isArray(r) && r[0] && r[1]) {
      publishDraftStart.value = r[0]
      publishDraftEnd.value = r[1]
      publishStep.value = 1
    } else {
      publishDraftStart.value = ''
      publishDraftEnd.value = ''
      publishStep.value = 0
    }
  } else if (publishStep.value === 1 && publishDraftStart.value && !publishDraftEnd.value) {
    publishStep.value = 0
    publishDraftStart.value = ''
  }
}

function onEffectivePopVisible(open) {
  if (open) {
    const r = effectiveDateRange.value
    if (Array.isArray(r) && r[0] && r[1]) {
      effectiveDraftStart.value = r[0]
      effectiveDraftEnd.value = r[1]
      effectiveStep.value = 1
    } else {
      effectiveDraftStart.value = ''
      effectiveDraftEnd.value = ''
      effectiveStep.value = 0
    }
  } else if (effectiveStep.value === 1 && effectiveDraftStart.value && !effectiveDraftEnd.value) {
    effectiveStep.value = 0
    effectiveDraftStart.value = ''
  }
}

function onPublishStartPicked(val) {
  if (!val) return
  publishDraftStart.value = val
  publishStep.value = 1
  publishDraftEnd.value = ''
}

function onPublishEndPicked(val) {
  const start = publishDraftStart.value
  if (!val || !start) return
  publishDateRange.value = [start, val]
  publishPopVisible.value = false
  runSearch({ resetPage: true })
}

function repickPublishStart() {
  publishStep.value = 0
  publishDraftStart.value = ''
  publishDraftEnd.value = ''
  publishDateRange.value = null
}

function clearPublishRange() {
  publishDateRange.value = null
  publishDraftStart.value = ''
  publishDraftEnd.value = ''
  publishStep.value = 0
  publishPopVisible.value = false
  runSearch({ resetPage: true })
}

function onEffectiveStartPicked(val) {
  if (!val) return
  effectiveDraftStart.value = val
  effectiveStep.value = 1
  effectiveDraftEnd.value = ''
}

function onEffectiveEndPicked(val) {
  const start = effectiveDraftStart.value
  if (!val || !start) return
  effectiveDateRange.value = [start, val]
  effectivePopVisible.value = false
  runSearch({ resetPage: true })
}

function repickEffectiveStart() {
  effectiveStep.value = 0
  effectiveDraftStart.value = ''
  effectiveDraftEnd.value = ''
  effectiveDateRange.value = null
}

function clearEffectiveRange() {
  effectiveDateRange.value = null
  effectiveDraftStart.value = ''
  effectiveDraftEnd.value = ''
  effectiveStep.value = 0
  effectivePopVisible.value = false
  runSearch({ resetPage: true })
}

function togglePublishPop() {
  publishPopVisible.value = !publishPopVisible.value
}

function toggleEffectivePop() {
  effectivePopVisible.value = !effectivePopVisible.value
}

watch(publishPopVisible, onPublishPopVisible)
watch(effectivePopVisible, onEffectivePopVisible)

async function loadMeta() {
  try {
    meta.value = { ...meta.value, ...(await getLawMeta()) }
  } catch (e) {
    error.value = e.message
  }
}

async function runSearch(opts = {}) {
  if (opts.resetPage) {
    form.page = 1
  }
  syncDateRangesToForm()
  loading.value = true
  error.value = ''
  try {
    response.value = await searchLaws(form)
    if (response.value?.page != null) {
      form.page = response.value.page
    }
    if (response.value?.size != null) {
      form.size = response.value.size
    }
    persistLawSearchSnapshot()
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
    hasSearched.value = true
  }
}

function onPageChange(p) {
  form.page = p
  runSearch()
}

function onPageSizeChange(s) {
  form.size = s
  runSearch({ resetPage: true })
}

function resetFilters() {
  form.categories = []
  form.statuses = []
  form.issuingBodies = []
  form.publishDateFrom = ''
  form.publishDateTo = ''
  form.effectiveDateFrom = ''
  form.effectiveDateTo = ''
  publishDateRange.value = null
  effectiveDateRange.value = null
  publishPopVisible.value = false
  publishStep.value = 0
  publishDraftStart.value = ''
  publishDraftEnd.value = ''
  effectivePopVisible.value = false
  effectiveStep.value = 0
  effectiveDraftStart.value = ''
  effectiveDraftEnd.value = ''
  form.page = 1
  runSearch()
}

function formatSyncResult(result) {
  if (result?.queued) {
    return `同步任务已提交后台执行：${result.taskId || ''}`
  }
  let msg = `同步完成：处理 ${result.fetchedPages} 个列表/页面，写入 ${result.upsertedArticles} 条条目`
  if (result.skippedItems != null && result.skippedItems > 0) {
    msg += `，跳过已存在 ${result.skippedItems} 项`
  }
  if (result.embeddedArticles != null) {
    msg += `，向量补全 ${result.embeddedArticles} 条`
  }
  if (result.failedUrls?.length) {
    msg += `。失败 ${result.failedUrls.length} 项（示例：${result.failedUrls.slice(0, 2).join('；')}）`
  }
  return msg
}

async function triggerUrlSync() {
  if (!sourceUrl.value.trim()) {
    syncMessage.value = '请填写法规详情页 URL，或点击下方「国家法规库」同步。'
    return
  }
  syncing.value = true
  syncMessage.value = ''
  error.value = ''
  try {
    const result = await syncLaws({ urls: [sourceUrl.value.trim()] })
    syncMessage.value = formatSyncResult(result)
    sourceUrl.value = ''
    await loadMeta()
    await runSearch({ resetPage: true })
  } catch (e) {
    error.value = e.message
  } finally {
    syncing.value = false
  }
}

/** 全国人大常委会国家法律法规数据库（flk.npc.gov.cn）公开接口 */
async function triggerNpcFlkSync() {
  syncing.value = true
  syncMessage.value = ''
  error.value = ''
  try {
    const result = await syncLaws({
      provider: 'npc-flk',
      npcSearchContent: form.q,
      npcSearchRange: form.q ? 2 : 1,
      npcSearchType: 2,
      npcType: '',
      npcCategoryLabels: [...(form.categories || [])],
      npcStatusCodes: [],
      npcPageStart: 1,
      npcPageSize: 20,
      npcMaxLaws: 1,
      npcStreamUntilDetailQuota: true,
      npcFetchAllListPages: false,
    })
    syncMessage.value = formatSyncResult(result)
    await loadMeta()
    await runSearch({ resetPage: true })
  } catch (e) {
    error.value = e.message
  } finally {
    syncing.value = false
  }
}

function copyCitation(item) {
  const title = `${lawTitleBracket(item)}${item.articleNo ? item.articleNo : ''}`
  let source
  if (isNpcFlkNationalDatabase(item)) {
    source = '来源：国家法律法规数据库（正式文本以官网公布为准）'
  } else if (isHiddenSourceUrl(item.sourceUrl)) {
    source = `来源：${item.sourceName || '本地法规库'}`
  } else {
    source = `来源：${item.sourceUrl}`
  }
  const interp =
    interpretationPlainForCopy(itemInterpretationClean(item).trim()) || LAW_INTERPRETATION_EMPTY
  const text = `${title}\n${source}\n通俗解读：${interp}`
  navigator.clipboard?.writeText(text)
}

function openLawArticleTop(articleId) {
  router.push({ path: `/law/${articleId}`, hash: '' })
}

function persistLawSearchSnapshot() {
  try {
    const payload = {
      v: 1,
      form: JSON.parse(JSON.stringify(form)),
      response: JSON.parse(JSON.stringify(response.value)),
      hasSearched: hasSearched.value,
      publishDateRange: publishDateRange.value,
      effectiveDateRange: effectiveDateRange.value,
    }
    sessionStorage.setItem(LAW_SEARCH_SNAPSHOT_KEY, JSON.stringify(payload))
  } catch {
    /* ignore quota / cycle */
  }
}

function tryRestoreLawSearchFromSession() {
  try {
    const raw = sessionStorage.getItem(LAW_SEARCH_SNAPSHOT_KEY)
    if (!raw) return
    const data = JSON.parse(raw)
    if (data.v !== 1 || !data.form) return
    Object.assign(form, data.form)
    if (data.response) response.value = data.response
    if (typeof data.hasSearched === 'boolean') hasSearched.value = data.hasSearched
    publishDateRange.value = data.publishDateRange ?? null
    effectiveDateRange.value = data.effectiveDateRange ?? null
  } catch {
    /* ignore */
  }
}

function lawTitleBracket(item) {
  const n = (item.lawName || '').trim()
  if (!n) return ''
  return n.startsWith('《') && n.endsWith('》') ? n : `《${n}》`
}

function itemInterpretationClean(item) {
  return stripBundledInterpretationSections(stripNpcInterpretationDisclaimer(item?.interpretation || ''))
}

/** 卡片内条文摘录（按条展示，不含附件路径噪声） */
function itemContentExcerpt(item) {
  const raw = item?.content
  if (!raw || !String(raw).trim()) return ''
  const cleaned = stripTechnicalNoiseFromInterpretation(String(raw))
  return excerptLawContent(cleaned, CONTENT_EXCERPT_LIST_MAX).excerpt
}

function itemExcerptBlock(item) {
  const excerpt = itemContentExcerpt(item)
  if (!excerpt) return { show: false, head: '', body: '' }
  const { head, body } = splitLeadingArticleHeading(excerpt)
  return { show: true, head, body: body || excerpt }
}

function itemInterpretationBlock(item) {
  const full = itemInterpretationClean(item).trim()
  if (!full) return { show: false, head: '', bodyHtml: '' }
  const { head, body } = splitLeadingArticleHeading(full)
  const rest = head ? body : full
  return { show: true, head, bodyHtml: interpretationBodyToHtml(rest) }
}

/** 标题行已与法规名+条文重复时不重复展示数据库 title */
function showLawCardSubtitle(item) {
  const t = (item.title || '').trim()
  if (!t) return false
  const b = lawTitleBracket(item)
  const art = (item.articleNo || '').trim()
  if (t === b || t === `${b}${art}` || t === `${b} ${art}`) return false
  if (art === '全文' && (t === `${b}全文` || t === `${b} 全文`)) return false
  return true
}

onMounted(async () => {
  await loadMeta()
  let restorePending = false
  try {
    restorePending = sessionStorage.getItem(LAW_SEARCH_RESTORE_FLAG) === '1'
    if (restorePending) sessionStorage.removeItem(LAW_SEARCH_RESTORE_FLAG)
  } catch {
    /* ignore */
  }
  if (restorePending) tryRestoreLawSearchFromSession()
})
</script>

<template>
  <AppLayout>
    <div class="page">
      <div class="wrap">
        <div class="crumbs">
          <a class="link" href="#" @click.prevent="router.push('/dashboard')">主页</a>
          <span class="material-symbols-outlined chev">chevron_right</span>
          <span class="here">法条检索</span>
        </div>

        <div class="hero">
          <div class="heroInner">
            <h1 class="h1">智能法条知识检索</h1>
            <p class="sub">
              主检索框支持<strong>关键词</strong>、<strong>案由</strong>与<strong>条文编号</strong>检索；结果按「条」独立成卡，同一法规共用法条标题、条号与内容各不相同。
              下方筛选区可用分类、机关、时效性与公布/施行日期区间缩小范围；通俗解读附适用场景与例外提示。
            </p>
          </div>
          <div class="heroSearch">
            <span class="material-symbols-outlined heroIcon">search</span>
            <input
              v-model.trim="form.q"
              class="heroInput"
              :placeholder="`${searchTypeLabel}，例如：劳动合同解除、民间借贷利率、第五百七十七条`"
              @keyup.enter="runSearch({ resetPage: true })"
            />
            <button class="heroBtn" type="button" :disabled="loading" @click="runSearch({ resetPage: true })">
              {{ loading ? '检索中' : '检索法条' }}
            </button>
          </div>
        </div>

        <section class="facetPanel" aria-label="筛选条件">
          <div class="facetHead">
            <h2 class="facetH2">
              <span class="material-symbols-outlined">filter_list</span>
              筛选条件
            </h2>
            <button type="button" class="filterReset" @click="resetFilters">重置筛选</button>
          </div>
          <div class="facetToolbar">
            <span v-if="hasSearched" class="toolTotal">
              为您找到 <strong>{{ response.total }}</strong> 条相关法条
              <template v-if="response.total > 0">
                · 第 <strong>{{ form.page }}</strong> / {{ totalPages }} 页（每页 {{ form.size }} 条）
              </template>
            </span>
            <span v-else class="toolTotal muted">设置筛选与排序后点击「检索法条」</span>
            <div class="toolSort">
              <span class="toolLab">排序</span>
              <el-select
                v-model="form.sort"
                class="sortSelect"
                placeholder="排序方式"
                @change="runSearch({ resetPage: true })"
              >
                <el-option label="相关度" value="relevance" />
                <el-option label="最新更新（入库）" value="latest" />
                <el-option label="最早更新（入库）" value="oldest" />
                <el-option label="公布日期（新→旧）" value="publishLatest" />
                <el-option label="公布日期（旧→新）" value="publishOldest" />
                <el-option label="法律名称" value="lawName" />
                <el-option label="条文编号" value="articleNo" />
              </el-select>
            </div>
          </div>
          <p class="facetHint">
            法律法规分类、制定机关、时效性支持多选；公布/施行日期点输入框内日历图标，在弹层中先选起始、再选截止（闭区间），可选填。
          </p>
          <div class="facetSelectRow">
            <div class="facetField">
              <div class="facetLab">法律法规分类</div>
              <el-select
                v-model="form.categories"
                class="facetSelect"
                multiple
                filterable
                collapse-tags
                collapse-tags-tooltip
                :max-collapse-tags="3"
                clearable
                placeholder="请选择法律法规分类"
                @change="runSearch({ resetPage: true })"
              >
                <el-option
                  v-for="opt in categoryOptions"
                  :key="'c-' + opt.value"
                  :label="opt.label"
                  :value="opt.value"
                />
              </el-select>
            </div>
            <div class="facetField">
              <div class="facetLab">制定机关</div>
              <el-select
                v-model="form.issuingBodies"
                class="facetSelect"
                multiple
                filterable
                collapse-tags
                collapse-tags-tooltip
                :max-collapse-tags="3"
                clearable
                placeholder="请选择制定机关"
                @change="runSearch({ resetPage: true })"
              >
                <el-option
                  v-for="opt in issuingBodyOptions"
                  :key="'i-' + opt.value"
                  :label="opt.label"
                  :value="opt.value"
                />
              </el-select>
            </div>
            <div class="facetField">
              <div class="facetLab">时效性</div>
              <el-select
                v-model="form.statuses"
                class="facetSelect"
                multiple
                filterable
                collapse-tags
                collapse-tags-tooltip
                :max-collapse-tags="3"
                clearable
                placeholder="请选择时效性"
                @change="runSearch({ resetPage: true })"
              >
                <el-option
                  v-for="opt in timelinessOptions"
                  :key="'s-' + opt.value"
                  :label="opt.label"
                  :value="opt.value"
                />
              </el-select>
            </div>
          </div>
          <div class="dateGrid">
            <div class="facetField">
              <div class="facetLab">公布日期</div>
              <el-popover
                v-model:visible="publishPopVisible"
                placement="bottom-start"
                :width="300"
                trigger="manual"
                popper-class="facetLawDatePopover"
              >
                <template #reference>
                  <div
                    class="facetDateIconRef"
                    :title="publishRangeTitle"
                    role="button"
                    tabindex="0"
                    @click="togglePublishPop"
                    @keydown.enter.prevent="togglePublishPop"
                  >
                    <el-input
                      readonly
                      class="facetDateIconInp"
                      :class="{ 'facetDateIconInp--filled': publishFieldDisplay }"
                      :model-value="publishFieldDisplay"
                      :placeholder="publishInputGuide"
                      aria-label="选择公布日期区间"
                    >
                      <template #suffix>
                        <span class="material-symbols-outlined facetDateIconGlyph" aria-hidden="true">calendar_month</span>
                      </template>
                    </el-input>
                  </div>
                </template>
                <div class="seqDatePop" @click.stop>
                  <template v-if="publishStep === 0">
                    <p class="seqDateHint">第 1 步：选择起始日期</p>
                    <el-date-picker
                      v-model="publishDraftStart"
                      class="seqDatePicker"
                      type="date"
                      value-format="YYYY-MM-DD"
                      placeholder="起始日期"
                      :editable="false"
                      :teleported="false"
                      @change="onPublishStartPicked"
                    />
                  </template>
                  <template v-else>
                    <p class="seqDateHint">第 2 步：选择截止日期</p>
                    <p class="seqDateFrom">起始：{{ publishDraftStart }}</p>
                    <el-date-picker
                      v-model="publishDraftEnd"
                      class="seqDatePicker"
                      type="date"
                      value-format="YYYY-MM-DD"
                      placeholder="截止日期"
                      :editable="false"
                      :teleported="false"
                      :disabled-date="disabledEndBeforeStart(publishDraftStart)"
                      @change="onPublishEndPicked"
                    />
                    <button type="button" class="seqDateLink" @click="repickPublishStart">重选起始</button>
                  </template>
                  <button
                    v-if="publishDateRange?.length === 2"
                    type="button"
                    class="seqDateClear"
                    @click="clearPublishRange"
                  >
                    清除区间
                  </button>
                </div>
              </el-popover>
            </div>
            <div class="facetField">
              <div class="facetLab">施行日期</div>
              <el-popover
                v-model:visible="effectivePopVisible"
                placement="bottom-start"
                :width="300"
                trigger="manual"
                popper-class="facetLawDatePopover"
              >
                <template #reference>
                  <div
                    class="facetDateIconRef"
                    :title="effectiveRangeTitle"
                    role="button"
                    tabindex="0"
                    @click="toggleEffectivePop"
                    @keydown.enter.prevent="toggleEffectivePop"
                  >
                    <el-input
                      readonly
                      class="facetDateIconInp"
                      :class="{ 'facetDateIconInp--filled': effectiveFieldDisplay }"
                      :model-value="effectiveFieldDisplay"
                      :placeholder="effectiveInputGuide"
                      aria-label="选择施行日期区间"
                    >
                      <template #suffix>
                        <span class="material-symbols-outlined facetDateIconGlyph" aria-hidden="true">calendar_month</span>
                      </template>
                    </el-input>
                  </div>
                </template>
                <div class="seqDatePop" @click.stop>
                  <template v-if="effectiveStep === 0">
                    <p class="seqDateHint">第 1 步：选择起始日期</p>
                    <el-date-picker
                      v-model="effectiveDraftStart"
                      class="seqDatePicker"
                      type="date"
                      value-format="YYYY-MM-DD"
                      placeholder="起始日期"
                      :editable="false"
                      :teleported="false"
                      @change="onEffectiveStartPicked"
                    />
                  </template>
                  <template v-else>
                    <p class="seqDateHint">第 2 步：选择截止日期</p>
                    <p class="seqDateFrom">起始：{{ effectiveDraftStart }}</p>
                    <el-date-picker
                      v-model="effectiveDraftEnd"
                      class="seqDatePicker"
                      type="date"
                      value-format="YYYY-MM-DD"
                      placeholder="截止日期"
                      :editable="false"
                      :teleported="false"
                      :disabled-date="disabledEndBeforeStart(effectiveDraftStart)"
                      @change="onEffectiveEndPicked"
                    />
                    <button type="button" class="seqDateLink" @click="repickEffectiveStart">重选起始</button>
                  </template>
                  <button
                    v-if="effectiveDateRange?.length === 2"
                    type="button"
                    class="seqDateClear"
                    @click="clearEffectiveRange"
                  >
                    清除区间
                  </button>
                </div>
              </el-popover>
            </div>
          </div>
        </section>

        <details class="syncFold">
          <summary>法规库与数据同步</summary>
          <div class="syncBody">
            <p class="syncHint">
              数据爬取与「实时法规库」均对接
              <a :href="npcSearchUrl" class="inlineNpc" target="_blank" rel="noopener noreferrer">{{
                npcSearchUrl
              }}</a>
              ：默认按<strong>法律法规分类</strong>映射中央层级效力类型（可在上方筛选多选分类后再同步）；列表按公布日期排序以弱化站内默认顺序。亦可粘贴法规详情页 URL 单页抓取。
            </p>
            <input v-model.trim="sourceUrl" class="syncInput" placeholder="法规详情页 URL（可选）" />
            <div class="syncBtns">
              <button type="button" class="npcBtn" :disabled="syncing" @click="triggerNpcFlkSync">
                <span class="material-symbols-outlined">cloud_download</span>
                {{ syncing ? '同步中' : '国家法规库' }}
              </button>
              <button type="button" class="urlBtn" :disabled="syncing" @click="triggerUrlSync">
                <span class="material-symbols-outlined">sync</span>
                {{ syncing ? '同步中' : '同步 URL' }}
              </button>
            </div>
            <p v-if="syncMessage" class="syncMsg">{{ syncMessage }}</p>
          </div>
        </details>

        <div class="list">
          <div v-if="error" class="empty error">{{ error }}</div>
          <div v-else-if="loading" class="empty">正在检索法规库...</div>
          <div v-else-if="!hasSearched" class="empty hint">
            尚未发起检索。可设置筛选条件或直接点击「检索法条」。
          </div>
          <div v-else-if="!response.items.length" class="empty">未找到匹配法条，请调整关键词或筛选条件。</div>

          <template v-else>
            <article v-for="item in response.items" :key="item.id" class="lawCard">
            <div class="leftBar"></div>
            <div class="lawCardInner">
              <div class="head">
                <div>
                  <div class="mini">
                    <span class="badge">{{ item.status }}</span>
                    <span class="miniText">{{ item.scoreLabel }} · {{ item.sourceName }}</span>
                  </div>
                  <h2 class="cardMainTitle">{{ lawTitleBracket(item) }}</h2>
                  <p class="cardArticleLine">
                    <span class="cardArticleLab">条文编号</span>
                    <span class="cardArticleVal">{{ item.articleNo }}</span>
                  </p>
                  <p v-if="showLawCardSubtitle(item)" class="lawHeadSubtitle">{{ item.title }}</p>
                  <div class="lawMetaStrip" aria-label="法规属性">
                    <span v-if="item.category" class="metaChip">分类：{{ item.category }}</span>
                    <span v-if="item.level" class="metaChip">效力位阶：{{ item.level }}</span>
                    <span v-if="item.issuingBody" class="metaChip">制定机关：{{ item.issuingBody }}</span>
                    <span v-if="item.status" class="metaChip">时效性：{{ item.status }}</span>
                    <span v-if="formatLawDate(item.publishDate)" class="metaChip"
                      >公布：{{ formatLawDate(item.publishDate) }}</span
                    >
                    <span v-if="formatLawDate(item.effectiveDate)" class="metaChip"
                      >施行：{{ formatLawDate(item.effectiveDate) }}</span
                    >
                  </div>
                </div>
                <button class="bookmark" type="button" @click="copyCitation(item)" title="复制引用">
                  <span class="material-symbols-outlined">content_copy</span>
                </button>
              </div>

              <template v-for="ex in [itemExcerptBlock(item)]" :key="'ex-' + item.id">
                <div v-if="ex.show" class="subCard excerptCard">
                  <h4><span class="material-symbols-outlined">article</span> 条文摘录</h4>
                  <div v-if="ex.head" class="articleNoLine">{{ ex.head }}</div>
                  <p class="excerptBody" :class="{ excerptBodyFollow: ex.head }">{{ ex.body }}</p>
                </div>
              </template>

              <div class="subCard">
                <h4><span class="material-symbols-outlined">translate</span> 通俗解读</h4>
                <template v-for="intr in [itemInterpretationBlock(item)]" :key="'intr-' + item.id">
                  <template v-if="intr.show">
                    <div v-if="intr.head" class="articleNoLine">{{ intr.head }}</div>
                    <p
                      class="interpBody interpBodyRich"
                      :class="{ interpBodyFollow: intr.head }"
                      v-html="intr.bodyHtml"
                    ></p>
                  </template>
                  <p v-else class="interpEmpty">{{ LAW_INTERPRETATION_EMPTY }}</p>
                </template>
                <p v-if="!isNpcFlkNationalDatabase(item)" class="sourceHint sourceHintInCard">
                  <template v-if="!isHiddenSourceUrl(item.sourceUrl)">
                    <span>来源：</span>
                    <a :href="item.sourceUrl" target="_blank" rel="noopener noreferrer" class="inlineSrc">{{
                      item.sourceUrl
                    }}</a>
                    <span v-if="item.sourceName" class="srcSep">（{{ item.sourceName }}）</span>
                  </template>
                  <template v-else>来源：{{ item.sourceName || '本地法规库' }}</template>
                </p>
              </div>

              <div class="foot">
                <div class="footMain">
                  <div class="footBlock">
                    <h4><span class="material-symbols-outlined">sell</span> 适用场景</h4>
                    <div v-if="item.scenarios?.length" class="tags">
                      <span v-for="tag in item.scenarios" :key="'s' + tag" class="tagScenario">{{ tag }}</span>
                    </div>
                    <p v-else class="footMuted">未标注典型场景。</p>
                  </div>
                  <div class="footBlock">
                    <h4><span class="material-symbols-outlined">folder_special</span> 关联案由</h4>
                    <div v-if="item.causeOfAction?.length" class="tags">
                      <span v-for="tag in item.causeOfAction" :key="'c' + tag" class="tagCause">{{ tag }}</span>
                    </div>
                    <p v-else class="footMuted">未关联案由标签。</p>
                  </div>
                  <div class="footBlock full">
                    <h4><span class="material-symbols-outlined">rule</span> 例外情况</h4>
                    <p v-if="item.exceptionsText && item.exceptionsText.trim()" class="exception">{{
                      item.exceptionsText
                    }}</p>
                    <p v-else class="exception exceptionMuted">{{ LAW_EXCEPTION_EMPTY }}</p>
                  </div>
                </div>
                <div class="btns">
                  <a
                    v-if="!isHiddenSourceUrl(item.sourceUrl)"
                    class="btn src"
                    :href="item.sourceUrl"
                    target="_blank"
                    rel="noopener noreferrer"
                  >
                    来源页面
                  </a>
                  <button class="btn ghost" type="button" @click="openLawArticleTop(item.id)">查看原文</button>
                </div>
              </div>
            </div>
          </article>
            <el-pagination
              v-if="hasSearched && !error && response.total > 0"
              class="lawPager"
              background
              layout="total, prev, pager, next, jumper"
              :page-sizes="pageSizeOptions"
              :page-size="form.size"
              :total="response.total"
              :current-page="form.page"
              @size-change="onPageSizeChange"
              @current-change="onPageChange"
            />
          </template>
        </div>
      </div>
    </div>
  </AppLayout>
</template>

<style scoped>
.page { height: 100%; overflow: auto; }
.wrap { max-width: 1280px; margin: 0 auto; padding: 24px; }

.crumbs {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 16px;
  font-size: 14px;
  color: var(--on-surface-variant);
}
.link { color: var(--on-surface-variant); text-decoration: none; }
.link:hover { color: var(--primary); }
.chev { font-size: 14px; color: var(--outline); }
.here { color: var(--on-surface); font-weight: 500; }

.hero {
  position: relative;
  overflow: hidden;
  border-radius: 12px;
  border: 1px solid var(--surface-variant);
  background: linear-gradient(135deg, var(--surface) 0%, var(--secondary-container) 100%);
  padding: 32px;
  margin-bottom: 16px;
}
.heroInner { text-align: center; max-width: 820px; margin: 0 auto 20px; }
.h1 { margin: 0 0 8px; font-size: 40px; letter-spacing: -0.02em; color: var(--on-surface); }
.sub { margin: 0; font-size: 18px; color: var(--on-surface-variant); line-height: 1.7; }
.heroSearch {
  max-width: 900px;
  margin: 0 auto;
  position: relative;
  display: flex;
  align-items: center;
}
.heroIcon {
  position: absolute;
  left: 14px;
  color: var(--primary);
  pointer-events: none;
}
.heroInput {
  width: 100%;
  height: 56px;
  border: 1px solid var(--outline-variant);
  border-radius: 12px;
  background: var(--surface);
  padding: 0 132px 0 48px;
  font-size: 16px;
  color: var(--on-surface);
  outline: none;
}
.heroInput:focus {
  border-color: var(--primary);
  box-shadow: 0 0 0 2px var(--primary-fixed);
}
.heroBtn {
  position: absolute;
  right: 6px;
  top: 6px;
  bottom: 6px;
  border: none;
  border-radius: 8px;
  padding: 0 20px;
  background: var(--primary);
  color: var(--on-primary);
  font-weight: 600;
  cursor: pointer;
}
.heroBtn:hover { background: var(--tertiary); }
.heroBtn:disabled { opacity: 0.65; cursor: wait; }

.toolTotal { font-size: 14px; color: var(--on-surface-variant); }
.toolTotal.muted { color: var(--secondary); }
.toolTotal strong { color: var(--primary); font-weight: 700; }
.toolSort {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}
.toolLab { font-size: 14px; font-weight: 600; color: var(--on-surface-variant); }
.sortSelect { width: 220px; }

.facetPanel {
  margin-bottom: 16px;
  padding: 16px 18px 18px;
  border-radius: 8px;
  border: 1px solid var(--surface-variant);
  background: var(--surface-container-low);
}
.facetHead {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}
.facetToolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--surface-variant);
}
.facetToolbar .toolTotal {
  min-width: 0;
  flex: 1 1 200px;
}
.facetH2 {
  margin: 0;
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 16px;
  font-weight: 700;
  color: var(--on-surface);
}
.facetH2 .material-symbols-outlined { font-size: 22px; color: var(--primary); }
.facetHint {
  margin: 0 0 12px;
  font-size: 13px;
  color: var(--secondary);
  line-height: 1.55;
}
.facetField .facetLab {
  font-size: 13px;
  font-weight: 600;
  color: var(--on-surface-variant);
  margin-bottom: 6px;
}
.facetSelectRow {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 14px 16px;
  margin-bottom: 16px;
}
.facetSelect {
  width: 100%;
}
.dateGrid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 12px 16px;
}
.facetDateIconRef {
  display: block;
  width: 100%;
  cursor: pointer;
}
.facetDateIconInp {
  width: 100%;
}
.facetDateIconInp :deep(.el-input__wrapper) {
  min-height: 40px;
  align-items: center;
  padding-left: 12px;
  padding-right: 10px;
  cursor: pointer;
}
.facetDateIconInp :deep(.el-input__inner) {
  cursor: pointer;
  font-size: 13px;
  line-height: 1.45;
  color: var(--on-surface-variant);
}
.facetDateIconInp :deep(.el-input__inner::placeholder) {
  color: var(--secondary);
  font-weight: 400;
}
.facetDateIconInp--filled :deep(.el-input__inner) {
  color: var(--on-surface);
  font-weight: 600;
}
.facetDateIconGlyph {
  font-size: 22px;
  color: var(--primary);
  line-height: 1;
  user-select: none;
  pointer-events: none;
}
.seqDatePop {
  display: flex;
  flex-direction: column;
  gap: 10px;
  overflow: visible;
}
.seqDateHint {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--on-surface);
}
.seqDateFrom {
  margin: 0;
  font-size: 12px;
  color: var(--secondary);
}
.seqDatePicker {
  width: 100%;
}
.seqDatePicker :deep(.el-input__wrapper) {
  min-height: 40px;
  align-items: center;
}
.seqDateLink {
  align-self: flex-start;
  padding: 0;
  border: none;
  background: none;
  color: var(--primary);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  text-decoration: underline;
  text-underline-offset: 2px;
}
.seqDateClear {
  margin-top: 4px;
  padding: 6px 10px;
  border-radius: 8px;
  border: 1px solid var(--surface-variant);
  background: var(--surface-container);
  color: var(--on-surface-variant);
  font-size: 13px;
  cursor: pointer;
}
.seqDateClear:hover {
  border-color: var(--outline-variant);
}

.filterReset {
  height: 34px;
  padding: 0 12px;
  border-radius: 8px;
  border: 1px solid transparent;
  background: transparent;
  color: var(--primary);
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
}
.filterReset:hover { background: color-mix(in srgb, var(--primary) 8%, transparent); }
.syncFold {
  margin-bottom: 24px;
  border-radius: 8px;
  border: 1px solid var(--surface-variant);
  background: var(--surface-container-lowest);
  overflow: hidden;
}
.syncFold summary {
  padding: 10px 14px;
  font-size: 14px;
  font-weight: 600;
  color: var(--on-surface-variant);
  cursor: pointer;
  list-style: none;
}
.syncFold summary::-webkit-details-marker { display: none; }
.syncFold[open] summary { border-bottom: 1px solid var(--surface-variant); }
.syncBody { padding: 14px 16px 16px; display: flex; flex-direction: column; gap: 10px; }
.syncHint { margin: 0; font-size: 13px; color: var(--secondary); line-height: 1.5; }
.syncInput {
  width: 100%;
  height: 40px;
  border: 1px solid var(--outline-variant);
  border-radius: 8px;
  padding: 0 12px;
  font-size: 14px;
  background: var(--surface);
  color: var(--on-surface);
  box-sizing: border-box;
}
.syncBtns { display: flex; flex-wrap: wrap; gap: 8px; }
.syncBtns button {
  flex: 1;
  min-width: 140px;
  height: 40px;
  border-radius: 8px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  cursor: pointer;
}
.npcBtn {
  border: 1px solid var(--primary);
  background: var(--primary-container);
  color: var(--on-primary-container);
}
.urlBtn {
  border: 1px solid var(--primary);
  background: transparent;
  color: var(--primary);
}
.syncBtns button:disabled { opacity: 0.65; cursor: wait; }
.syncMsg { margin: 0; color: #0f7a55; font-size: 13px; line-height: 1.5; }

.list { display: flex; flex-direction: column; gap: 16px; }

.lawPager {
  margin-top: 8px;
  padding: 8px 0 4px;
  justify-content: center;
  flex-wrap: wrap;
}

.inlineNpc {
  color: var(--primary);
  text-decoration: none;
  word-break: break-all;
}
.inlineNpc:hover {
  text-decoration: underline;
}
.empty {
  border: 1px solid var(--surface-variant);
  border-radius: 12px;
  background: var(--surface-container-lowest);
  box-shadow: var(--shadow-sm);
  padding: 18px;
  color: var(--on-surface-variant);
}
.empty.hint { border-style: dashed; }
.empty.error {
  background: var(--error-container);
  color: var(--on-error-container);
  border-color: transparent;
}

.lawCard {
  position: relative;
  overflow: hidden;
  border: 1px solid var(--surface-variant);
  border-radius: 12px;
  background: var(--surface-container-lowest);
  box-shadow: var(--shadow-sm);
  transition: box-shadow 0.2s;
}
.lawCard:hover { box-shadow: var(--shadow-md); }
.leftBar {
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 4px;
  background: var(--primary);
}
.lawCardInner { padding: 16px 16px 16px 20px; }

.head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}
.mini { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-bottom: 4px; }
.badge {
  font-size: 11px;
  font-weight: 700;
  padding: 4px 8px;
  border-radius: 6px;
  background: var(--tertiary-fixed);
  color: var(--on-tertiary-fixed);
}
.miniText { color: var(--secondary); font-size: 12px; }
.cardMainTitle {
  margin: 4px 0 0;
  font-size: clamp(20px, 2.4vw, 26px);
  font-weight: 800;
  letter-spacing: 0.02em;
  line-height: 1.35;
  color: var(--on-surface);
}
.cardArticleLine {
  margin: 10px 0 0;
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 8px;
  font-size: 14px;
  color: var(--on-surface-variant);
}
.cardArticleLab {
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: var(--secondary);
}
.cardArticleVal {
  font-weight: 600;
  color: var(--on-surface);
}
.lawHeadSubtitle {
  margin: 8px 0 0;
  font-size: 13px;
  color: var(--secondary);
  line-height: 1.5;
}
.lawMetaStrip {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
}
.metaChip {
  font-size: 12px;
  color: var(--on-surface-variant);
  padding: 4px 10px;
  border-radius: 999px;
  background: var(--surface-container);
  border: 1px solid var(--surface-variant);
}
/* 检索卡片主标题见 .cardMainTitle；保留其它 h2 默认以防遗漏 */
h2:not(.cardMainTitle) { margin: 0; font-size: 24px; line-height: 1.35; color: var(--on-surface); }
.bookmark {
  width: 40px;
  height: 40px;
  border: 1px solid var(--surface-variant);
  border-radius: 8px;
  background: var(--surface);
  color: var(--primary);
  cursor: pointer;
  flex-shrink: 0;
}
.block { margin-bottom: 16px; }
.block h4,
.foot h4 {
  margin: 0 0 8px;
  font-size: 12px;
  color: var(--secondary);
  text-transform: uppercase;
  letter-spacing: 0.05em;
  display: flex;
  align-items: center;
  gap: 6px;
}
.block h4 .material-symbols-outlined,
.foot h4 .material-symbols-outlined { font-size: 16px; color: var(--primary); }
.block p,
.lawBodyInline {
  margin: 0;
  color: var(--on-surface);
  line-height: 1.8;
  border-left: 2px solid var(--surface-variant);
  padding-left: 12px;
}
.lawBodyInline :deep(a) {
  color: var(--primary);
  word-break: break-all;
}
.lawBodyInline :deep(a:hover) {
  text-decoration: underline;
}
.sourceHint {
  margin: 10px 0 0;
  padding-left: 12px;
  font-size: 12px;
  color: var(--secondary);
  line-height: 1.55;
}
.inlineSrc {
  color: var(--primary);
  font-weight: 600;
  text-decoration: none;
}
.inlineSrc:hover {
  text-decoration: underline;
}
.srcSep {
  color: var(--outline);
}
.srcSep.muted {
  font-weight: 400;
  font-size: 12px;
  margin-left: 4px;
}
.excerptCard {
  border-color: var(--outline-variant);
}
.articleNoLine {
  font-weight: 600;
  font-size: 15px;
  color: var(--on-surface);
  line-height: 1.55;
  margin: 0 0 10px;
}
.excerptBodyFollow {
  margin-top: 0;
}
.interpBodyFollow {
  margin-top: 0;
}
.excerptBody {
  font-size: 14px;
  color: var(--on-surface);
  white-space: pre-wrap;
}
.subCard {
  background: var(--surface);
  border: 1px solid var(--primary-fixed);
  border-radius: 8px;
  padding: 16px;
  margin-bottom: 16px;
}
.subCard h4 {
  margin: 0 0 8px;
  font-size: 12px;
  color: var(--primary);
  display: flex;
  align-items: center;
  gap: 6px;
  text-transform: uppercase;
  letter-spacing: 0.05em;
}
.subCard p { margin: 0; color: var(--on-surface-variant); line-height: 1.8; }
.interpBodyRich {
  text-align: justify;
  text-justify: inter-ideograph;
}
.interpBodyRich :deep(u.interp-em),
.interpBodyRich :deep(u) {
  text-decoration: underline;
  text-decoration-color: #c62828;
  text-underline-offset: 3px;
  text-decoration-thickness: 2px;
}
.interpEmpty {
  margin: 0;
  color: var(--secondary);
  font-style: italic;
  line-height: 1.75;
}
.foot {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  justify-content: space-between;
  gap: 12px;
}
.footMain {
  flex: 1 1 280px;
  display: grid;
  grid-template-columns: 1fr;
  gap: 14px;
}
@media (min-width: 720px) {
  .footMain {
    grid-template-columns: 1fr 1fr;
  }
  .footBlock.full {
    grid-column: 1 / -1;
  }
}
.footBlock h4 {
  margin: 0 0 8px;
}
.footMuted {
  margin: 0;
  font-size: 13px;
  color: var(--outline);
  line-height: 1.5;
}
.tags { display: flex; flex-wrap: wrap; gap: 8px; }
.tags span {
  font-size: 12px;
  color: var(--on-surface-variant);
  padding: 6px 10px;
  border-radius: 999px;
  background: var(--surface-container);
  border: 1px solid var(--surface-variant);
}
.tagScenario {
  border-color: var(--primary-fixed) !important;
  background: var(--primary-fixed-dim) !important;
  color: var(--on-primary-container) !important;
}
.tagCause {
  border-color: var(--tertiary-fixed) !important;
  background: var(--surface-container-high) !important;
}
.exception { margin: 0; color: var(--secondary); font-size: 13px; line-height: 1.6; }
.exceptionMuted {
  font-style: italic;
  color: var(--outline);
}
.btns { display: flex; flex-wrap: wrap; gap: 10px; }
.btn {
  height: 40px;
  padding: 0 14px;
  border-radius: 8px;
  font-weight: 600;
  cursor: pointer;
  border: 1px solid transparent;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  box-sizing: border-box;
  font-size: 14px;
}
.btn.src {
  background: transparent;
  border-color: var(--outline);
  color: var(--primary);
  text-decoration: none;
}
.btn.src:hover {
  background: var(--surface-container-high);
}
.btn.ghost { background: transparent; border-color: var(--primary); color: var(--primary); }
.btn.primary { background: var(--primary); color: var(--on-primary); box-shadow: var(--shadow-sm); }

@media (max-width: 640px) {
  .wrap { padding: 16px; }
  .hero { padding: 22px 18px; }
  .h1 { font-size: 28px; }
  .sub { font-size: 16px; }
  .heroInput { padding-right: 108px; font-size: 15px; }
  .heroBtn { padding: 0 12px; font-size: 14px; }
  .facetToolbar { flex-direction: column; align-items: stretch; }
  .facetToolbar .toolSort { width: 100%; justify-content: space-between; }
  .sortSelect { width: 100% !important; max-width: 100%; }
}
</style>

<style>
.facetLawDatePopover.el-popper {
  overflow: visible !important;
}
</style>
