<script setup>
import AppLayout from '../../components/common/AppLayout.vue'
import { LAW_SEARCH_RESTORE_FLAG } from '../../constants/lawSearchSession.js'
import { computed, nextTick, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getLawDetail } from '../../api/law'
import {
  formatLawBodyHtml,
  formatLawDate,
  interpretationBodyToHtml,
  isHiddenSourceUrl,
  isNpcFlkNationalDatabase,
  LAW_EXCEPTION_EMPTY,
  LAW_INTERPRETATION_EMPTY,
  normalizeLawChineseInline,
  splitLeadingArticleHeading,
  stripBundledInterpretationSections,
  stripNpcInterpretationDisclaimer,
} from '../../utils/lawDisplay'

const route = useRoute()
const router = useRouter()
const law = ref(null)
const loading = ref(true)
const error = ref('')
const id = computed(() => Number(route.params.id))

const showExternalSource = computed(
  () => law.value && !isHiddenSourceUrl(law.value.sourceUrl),
)

/** 通俗解读正文：去掉 NPC 声明与历史拼接的「适用/例外/风险提示」块（与后端分区一致） */
const interpretationPlain = computed(() => {
  if (!law.value) return ''
  return stripBundledInterpretationSections(
    stripNpcInterpretationDisclaimer(law.value.interpretation || ''),
  ).trim()
})

const isInterpretationEmpty = computed(() => !interpretationPlain.value)

/** 通俗解读：条号单独成行 */
const interpretationParts = computed(() => {
  const plain = interpretationPlain.value
  if (!plain) return { head: '', html: '', empty: true }
  const { head, body } = splitLeadingArticleHeading(plain)
  const rest = head ? body : plain
  return { head, html: interpretationBodyToHtml(rest), empty: false }
})

/** 法条原文：条号单独成行 */
const lawOriginalParts = computed(() => {
  const text = law.value?.content
  if (!text || !String(text).trim()) return { head: '', bodyHtml: '', empty: true }
  const plain = normalizeLawChineseInline(String(text).trim())
  const { head, body } = splitLeadingArticleHeading(plain)
  const rest = head ? body : plain
  if (!rest?.trim() && !head) return { head: '', bodyHtml: '', empty: true }
  return { head, bodyHtml: formatLawBodyHtml(rest), empty: false }
})

const exceptionsText = computed(() => {
  const s = law.value?.exceptionsText
  return s && String(s).trim() ? String(s).trim() : ''
})

const lawTitleDisplay = computed(() => {
  if (!law.value?.lawName) return ''
  const n = String(law.value.lawName).trim()
  return n.startsWith('《') && n.endsWith('》') ? n : `《${n}》`
})

async function loadDetail() {
  loading.value = true
  error.value = ''
  try {
    law.value = await getLawDetail(id.value)
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

function scrollDetailToTop() {
  requestAnimationFrame(() => {
    document.querySelector('.detailScroll')?.scrollTo({ top: 0, left: 0, behavior: 'auto' })
  })
}

async function applyDetailScroll() {
  await nextTick()
  if (route.hash === '#lawOriginalBlock') {
    scrollLawOriginalIfHash()
  } else {
    scrollDetailToTop()
  }
}

function markLawSearchRestoreAndGoList() {
  try {
    sessionStorage.setItem(LAW_SEARCH_RESTORE_FLAG, '1')
  } catch {
    /* ignore */
  }
  router.push('/law')
}

function back() {
  markLawSearchRestoreAndGoList()
}

function scrollLawOriginalIfHash() {
  if (route.hash !== '#lawOriginalBlock') return
  requestAnimationFrame(() => {
    document.getElementById('lawOriginalBlock')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  })
}

watch(
  () => id.value,
  async () => {
    if (!Number.isFinite(id.value) || id.value <= 0) return
    await loadDetail()
    await applyDetailScroll()
  },
  { immediate: true },
)

watch(
  () => route.hash,
  async () => {
    if (!law.value || !Number.isFinite(id.value) || id.value <= 0) return
    await applyDetailScroll()
  },
)
</script>

<template>
  <AppLayout>
    <div class="detailScroll">
      <div class="wrap">
      <div class="crumbs">
        <a class="link" href="#" @click.prevent="router.push('/dashboard')">首页</a>
        <span class="material-symbols-outlined chev">chevron_right</span>
        <a class="link" href="#" @click.prevent="markLawSearchRestoreAndGoList">法律模块</a>
        <span class="material-symbols-outlined chev">chevron_right</span>
        <span class="here">法条详情</span>
      </div>

      <div v-if="loading" class="empty">正在加载法条...</div>
      <div v-else-if="error" class="empty error">{{ error }}</div>

      <div v-else-if="law" class="card">
        <div class="head">
          <div class="badge">{{ law.status || '—' }}</div>
          <h1 class="mainLawTitle">{{ lawTitleDisplay }}</h1>
          <p class="articleNoRow">
            <span class="articleNoLab">条文编号</span>
            <span class="articleNoVal">{{ law.articleNo }}</span>
          </p>
          <p v-if="law.title && law.title.trim()" class="titleLine">{{ law.title }}</p>
        </div>

        <section class="infoSection" aria-label="法规信息">
          <h2 class="sectionCap">法规与条文信息</h2>
          <dl class="infoGrid">
            <div class="infoRow">
              <dt>法律法规标题</dt>
              <dd class="ddLawTitle">{{ lawTitleDisplay }}</dd>
            </div>
            <div class="infoRow">
              <dt>条文编号</dt>
              <dd>{{ law.articleNo }}</dd>
            </div>
            <div class="infoRow">
              <dt>法律法规分类</dt>
              <dd>{{ law.category || '—' }}</dd>
            </div>
            <div class="infoRow">
              <dt>效力位阶</dt>
              <dd>{{ law.level || '—' }}</dd>
            </div>
            <div class="infoRow">
              <dt>制定机关</dt>
              <dd>{{ law.issuingBody || '—' }}</dd>
            </div>
            <div class="infoRow">
              <dt>时效性</dt>
              <dd>{{ law.status || '—' }}</dd>
            </div>
            <div class="infoRow">
              <dt>公布日期</dt>
              <dd>{{ formatLawDate(law.publishDate) || '—' }}</dd>
            </div>
            <div class="infoRow">
              <dt>施行日期</dt>
              <dd>{{ formatLawDate(law.effectiveDate) || '—' }}</dd>
            </div>
            <div class="infoRow">
              <dt>地域效力</dt>
              <dd>{{ law.region || '—' }}</dd>
            </div>
            <div class="infoRow">
              <dt>数据来源</dt>
              <dd>{{ law.sourceName || '—' }}</dd>
            </div>
            <div v-if="showExternalSource && !isNpcFlkNationalDatabase(law)" class="infoRow">
              <dt>来源页面 / URL</dt>
              <dd>
                <a class="infoSourceLink" :href="law.sourceUrl" target="_blank" rel="noopener noreferrer">
                  {{ law.sourceUrl }}
                </a>
              </dd>
            </div>
            <div v-else-if="showExternalSource && isNpcFlkNationalDatabase(law)" class="infoRow">
              <dt>来源</dt>
              <dd>国家法律法规数据库（链接见页尾「打开来源页面」，此处不展示长地址）</dd>
            </div>
          </dl>
        </section>

        <div id="lawOriginalBlock" class="block lawOriginalBlock">
          <div class="cap">
            <span class="material-symbols-outlined">article</span>
            法条原文
          </div>
          <template v-if="!lawOriginalParts.empty">
            <div v-if="lawOriginalParts.head" class="articleNoLine">{{ lawOriginalParts.head }}</div>
            <div class="text lawOriginalRich" :class="{ lawOriginalFollow: lawOriginalParts.head }" v-html="lawOriginalParts.bodyHtml"></div>
          </template>
          <div v-else class="text emptyHint">暂未加载到可展示的法条原文，请打开来源页面核对官方文本。</div>
        </div>

        <div class="block sub interpretBlock">
          <div class="cap blue">
            <span class="material-symbols-outlined">translate</span>
            通俗解读
          </div>
          <div v-if="law.validityNotice" class="validityNotice" role="status">
            {{ law.validityNotice }}
          </div>
          <div
            v-if="isInterpretationEmpty"
            class="text subtext interpretMain emptyHint"
          >
            {{ LAW_INTERPRETATION_EMPTY }}
          </div>
          <template v-else>
            <div v-if="interpretationParts.head" class="articleNoLine">{{ interpretationParts.head }}</div>
            <div
              class="text subtext interpretMain interpretBodyRich"
              :class="{ interpretBodyIndent: interpretationParts.head }"
              v-html="interpretationParts.html"
            ></div>
          </template>
        </div>

        <div class="sourceBanner">
          <template v-if="showExternalSource">
            <p class="sourceLead">
              本页已按需加载法条原文与通俗解读；正式引用和版本核验仍请打开来源页面。
            </p>
            <a
              class="sourceBtn"
              :href="law.sourceUrl"
              target="_blank"
              rel="noopener noreferrer"
            >
              <span class="material-symbols-outlined">open_in_new</span>
              打开来源页面（{{ law.sourceName || '官方网站' }}）
            </a>
          </template>
          <p v-else class="sourceMuted">
            当前条目暂无可打开的外部来源页面；请结合本页全文、版本号和来源元数据审慎核对。
          </p>
        </div>

        <div class="columns">
          <div class="block">
            <div class="cap">
              <span class="material-symbols-outlined">sell</span>
              适用场景
            </div>
            <div v-if="law.scenarios?.length" class="tags">
              <span v-for="t in law.scenarios" :key="'s' + t" class="tag scenario">{{ t }}</span>
            </div>
            <p v-else class="miniMuted">未标注典型适用场景，请结合条文用语与案由理解。</p>
          </div>

          <div class="block">
            <div class="cap">
              <span class="material-symbols-outlined">folder_special</span>
              关联案由 / 纠纷类型
            </div>
            <div v-if="law.causeOfAction?.length" class="tags">
              <span v-for="t in law.causeOfAction" :key="'c' + t" class="tag cause">{{ t }}</span>
            </div>
            <p v-else class="miniMuted">未关联具体案由标签。</p>
          </div>
        </div>

        <div class="block warnBlock">
          <div class="cap warnCap">
            <span class="material-symbols-outlined">warning</span>
            例外情况与适用限制
          </div>
          <div v-if="exceptionsText" class="text">{{ exceptionsText }}</div>
          <div v-else class="text emptyHint">{{ LAW_EXCEPTION_EMPTY }}</div>
        </div>

        <div class="actions">
          <button class="btn ghost" type="button" @click="back">返回列表</button>
          <a
            v-if="showExternalSource"
            class="btn outlineLink"
            :href="law.sourceUrl"
            target="_blank"
            rel="noopener noreferrer"
          >
            来源页面
          </a>
        </div>
      </div>
    </div>
    </div>
  </AppLayout>
</template>

<style scoped>
/* AppLayout 主区 canvas 为 overflow:hidden，此处填满并可纵向滚动整页 */
.detailScroll {
  height: 100%;
  min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
  scrollbar-gutter: stable;
  scrollbar-width: thin;
  scrollbar-color: var(--outline) var(--surface-variant);
}
.detailScroll::-webkit-scrollbar {
  width: 11px;
}
.detailScroll::-webkit-scrollbar-track {
  background: var(--surface-variant);
  border-radius: 8px;
}
.detailScroll::-webkit-scrollbar-thumb {
  background: var(--outline);
  border-radius: 8px;
  border: 2px solid var(--surface-variant);
}
.detailScroll::-webkit-scrollbar-thumb:hover {
  background: var(--secondary);
}
.wrap {
  max-width: 1280px;
  margin: 0 auto;
  padding: 24px;
  padding-bottom: 40px;
}
.crumbs {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  color: var(--secondary);
  margin-bottom: 16px;
}
.chev {
  font-size: 16px;
  color: var(--outline);
}
.link {
  color: var(--primary);
  text-decoration: none;
}
.link:hover {
  text-decoration: underline;
}
.here {
  color: var(--on-surface);
}
.card,
.empty {
  background: var(--surface-container-lowest);
  border: 1px solid var(--surface-variant);
  border-radius: 12px;
  box-shadow: var(--shadow-sm);
  padding: 24px;
}
.empty {
  color: var(--secondary);
}
.empty.error {
  color: var(--on-error-container);
  background: var(--error-container);
  border-color: transparent;
}
.head {
  margin-bottom: 8px;
}
.badge {
  display: inline-flex;
  padding: 4px 8px;
  border-radius: 6px;
  background: var(--tertiary-fixed);
  color: var(--on-tertiary-fixed);
  font-size: 12px;
  font-weight: 700;
  margin-bottom: 8px;
}
.mainLawTitle {
  margin: 0;
  font-size: clamp(22px, 2.5vw, 32px);
  font-weight: 800;
  color: var(--on-surface);
  letter-spacing: 0.02em;
  line-height: 1.35;
}
.articleNoRow {
  margin: 10px 0 0;
  font-size: 15px;
  color: var(--on-surface-variant);
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 8px;
}
.articleNoLab {
  font-weight: 600;
  color: var(--secondary);
  font-size: 12px;
  letter-spacing: 0.06em;
  text-transform: uppercase;
}
.articleNoVal {
  font-weight: 600;
  color: var(--on-surface);
}
.titleLine {
  margin: 8px 0 0;
  font-size: 15px;
  color: var(--on-surface-variant);
  line-height: 1.5;
}
.infoSection {
  margin-top: 20px;
  padding: 16px 18px;
  border-radius: 10px;
  border: 1px solid var(--surface-variant);
  background: var(--surface-container-low);
}
.sectionCap {
  margin: 0 0 12px;
  font-size: 13px;
  font-weight: 700;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  color: var(--secondary);
}
.infoGrid {
  margin: 0;
  display: grid;
  grid-template-columns: 1fr;
  gap: 0;
}
@media (min-width: 720px) {
  .infoGrid {
    grid-template-columns: 160px 1fr;
  }
  .infoRow {
    display: contents;
  }
  .infoRow dt,
  .infoRow dd {
    padding: 8px 0;
    border-bottom: 1px solid var(--surface-variant);
  }
  .infoRow:last-child dt,
  .infoRow:last-child dd {
    border-bottom: none;
  }
}
.infoRow dt {
  font-size: 13px;
  color: var(--secondary);
  font-weight: 600;
}
.infoRow dd {
  margin: 0;
  font-size: 14px;
  color: var(--on-surface);
  line-height: 1.5;
}
.ddLawTitle {
  font-weight: 700;
  font-size: 15px;
  color: var(--on-surface);
}
.infoSourceLink {
  color: var(--primary);
  text-decoration: none;
  word-break: break-all;
}
.infoSourceLink:hover {
  text-decoration: underline;
}
@media (max-width: 719px) {
  .infoRow {
    display: grid;
    grid-template-columns: 1fr;
    gap: 4px;
    padding: 10px 0;
    border-bottom: 1px solid var(--surface-variant);
  }
  .infoRow:last-child {
    border-bottom: none;
  }
}
.sourceBanner {
  margin-top: 16px;
  padding: 14px 16px;
  border-radius: 10px;
  background: var(--primary-fixed-dim);
  border: 1px solid var(--primary-fixed);
}
.sourceLead {
  margin: 0 0 10px;
  font-size: 13px;
  color: var(--on-surface-variant);
  line-height: 1.55;
}
.interpretBlock .interpretMain.emptyHint {
  font-style: italic;
}
.articleNoLine {
  font-weight: 600;
  font-size: 16px;
  color: var(--on-surface);
  line-height: 1.55;
  margin: 0 0 12px;
}
.lawOriginalFollow {
  margin-top: 0;
}
.interpretBodyIndent {
  margin-top: 0;
}
.interpretBodyRich {
  line-height: 1.85;
  text-align: justify;
  text-justify: inter-ideograph;
}
/* 法条原文、通俗解读中的下划线强调：使用醒目红色 */
.lawOriginalRich :deep(u),
.interpretBodyRich :deep(u.interp-em),
.interpretBodyRich :deep(u) {
  text-decoration: underline;
  text-decoration-color: #c62828;
  text-underline-offset: 3px;
  text-decoration-thickness: 2px;
}
.validityNotice {
  margin: 0 0 12px;
  padding: 10px 12px;
  border-radius: 8px;
  border: 1px solid var(--outline-variant);
  background: var(--surface-container-high);
  font-size: 13px;
  line-height: 1.65;
  color: var(--on-surface-variant);
}
.scrollHint {
  margin: 0 0 8px;
  font-size: 12px;
  color: var(--secondary);
}
.excerptToolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
  padding: 12px 14px;
  border-radius: 10px;
  border: 1px dashed var(--outline-variant);
  background: var(--surface-container-low);
}
.excerptMeta {
  margin: 0;
  flex: 1 1 220px;
  font-size: 13px;
  color: var(--on-surface-variant);
  line-height: 1.55;
}
.excerptMeta strong {
  color: var(--primary);
  font-weight: 700;
}
.excerptActions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
}
.btn.sm {
  height: 36px;
  padding: 0 12px;
  font-size: 13px;
  gap: 6px;
}
.btn.sm .btnIc {
  font-size: 18px;
}
.lawOriginalBlock {
  margin-top: 16px;
}
.lawBodyScroll {
  max-height: min(70vh, 640px);
  overflow-y: scroll;
  overflow-x: auto;
  padding: 14px 16px;
  border-radius: 10px;
  border: 1px solid var(--surface-variant);
  background: var(--surface);
  scrollbar-gutter: stable;
  scrollbar-width: thin;
  scrollbar-color: var(--outline) var(--surface-variant);
}
.lawBodyScroll::-webkit-scrollbar {
  width: 11px;
}
.lawBodyScroll::-webkit-scrollbar-track {
  background: var(--surface-variant);
  border-radius: 8px;
}
.lawBodyScroll::-webkit-scrollbar-thumb {
  background: var(--outline);
  border-radius: 8px;
  border: 2px solid var(--surface-variant);
}
.lawBodyScroll::-webkit-scrollbar-thumb:hover {
  background: var(--secondary);
}
.lawBodyScroll.excerptCompact {
  max-height: min(42vh, 400px);
}
.lawBodyInner {
  padding-left: 0;
  border-left: none;
  min-height: min-content;
}
.sourceMuted {
  margin: 0;
  font-size: 13px;
  color: var(--on-surface-variant);
  line-height: 1.55;
}
.sourceBtn {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 10px 16px;
  border-radius: 8px;
  background: var(--primary);
  color: var(--on-primary);
  font-weight: 600;
  font-size: 14px;
  text-decoration: none;
  box-shadow: var(--shadow-sm);
}
.sourceBtn:hover {
  box-shadow: var(--shadow-md);
}
.sourceBtn .material-symbols-outlined {
  font-size: 20px;
}
.block {
  margin-top: 16px;
}
.columns {
  display: grid;
  grid-template-columns: 1fr;
  gap: 16px;
}
@media (min-width: 900px) {
  .columns {
    grid-template-columns: 1fr 1fr;
  }
}
.cap {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.05em;
  text-transform: uppercase;
  color: var(--secondary);
  margin-bottom: 8px;
}
.cap.blue {
  color: var(--primary-container);
}
.warnCap {
  color: var(--on-error-container);
}
.text {
  color: var(--on-surface);
  line-height: 1.8;
  padding-left: 12px;
  border-left: 2px solid var(--surface-variant);
}
.lawBodyInner.text {
  padding-left: 12px;
  border-left: 2px solid var(--surface-variant);
}
.lawBody :deep(a) {
  color: var(--primary);
  word-break: break-all;
}
.lawBody :deep(a:hover) {
  text-decoration: underline;
}
.sub {
  background: var(--surface-bright);
  border: 1px solid var(--primary-fixed);
  border-radius: 12px;
  padding: 16px;
}
.subtext {
  color: var(--on-surface-variant);
  border-left-color: var(--primary-fixed-dim);
}
.emptyHint {
  color: var(--secondary);
  font-style: italic;
  border-left-color: var(--outline-variant);
}
.warnBlock {
  padding: 16px;
  border-radius: 12px;
  border: 1px solid var(--outline-variant);
  background: var(--surface-container);
}
.miniMuted {
  margin: 0;
  padding-left: 12px;
  font-size: 13px;
  color: var(--secondary);
  line-height: 1.6;
}
.tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.tag {
  padding: 6px 10px;
  border-radius: 999px;
  border: 1px solid var(--surface-variant);
  background: var(--surface-container);
  font-size: 12px;
}
.tag.scenario {
  border-color: var(--primary-fixed);
  color: var(--on-primary-container);
  background: var(--primary-fixed-dim);
}
.tag.cause {
  border-color: var(--tertiary-fixed);
  color: var(--on-tertiary-fixed);
  background: var(--surface-container-high);
}
.actions {
  margin-top: 18px;
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 10px;
}
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
.btn.ghost {
  background: transparent;
  border-color: var(--primary);
  color: var(--primary);
}
.btn.ghost:hover {
  background: var(--primary-fixed);
}
.btn.primary {
  background: var(--primary);
  color: var(--on-primary);
  box-shadow: var(--shadow-sm);
}
.btn.primary:hover {
  box-shadow: var(--shadow-md);
}
.btn.outlineLink {
  background: transparent;
  border-color: var(--outline);
  color: var(--primary);
  text-decoration: none;
}
.btn.outlineLink:hover {
  background: var(--surface-container-high);
}
</style>
