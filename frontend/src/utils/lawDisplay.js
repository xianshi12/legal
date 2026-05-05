/** 法条展示：日期格式化、外链识别、正文中的 URL 安全链化 */

export const LAW_INTERPRETATION_EMPTY =
  '本条暂未生成专门的通俗摘要。建议您先查看法规要素、来源页面、适用场景与例外情况；涉及具体维权路径或责任构成时，请再打开权威来源核对正式文本。'

/** 去掉历史版本中写入解读末尾的 NPC 来源免责声明（存量数据兼容）。 */
/** 国家法律法规数据库（NPC）来源：列表/卡片内只展示名称，不展示长 URL */
export function isNpcFlkNationalDatabase(item) {
  if (!item) return false
  const name = String(item.sourceName || '')
  if (name.includes('国家法律法规数据库')) return true
  const u = String(item.sourceUrl || '')
  return u.includes('flk.npc.gov.cn')
}

/** 去掉解读里混入的附件路径、URL（与后端 stripTechnicalNoise 呼应，兼容存量数据） */
/** CJK 间误插空格、连续「。」等与后端 LawInterpretationTexts.normalizeCjkInline 对齐 */
export function normalizeLawChineseInline(text) {
  if (text == null || text === '') return ''
  let t = String(text)
    .replace(/\u00a0/g, ' ')
    .replace(/\u3000/g, ' ')
    .trim()
  t = t.replace(/\t|\f|\r/g, ' ').replace(/\s+/g, ' ')
  let prev
  do {
    prev = t
    t = t.replace(/([\u4e00-\u9fff\u3400-\u4dbf]) ([\u4e00-\u9fff\u3400-\u4dbf])/g, '$1$2')
  } while (t !== prev)
  t = t.replace(/ {2,}/g, ' ')
  t = t.replace(/。{2,}/g, '。')
  t = t.replace(/ ([，。；、：！？])/g, '$1')
  t = t.replace(/([，。；、：！？]) /g, '$1')
  return t.trim()
}

const LEGACY_DIGEST_LEAD_IN =
  /^针对「[^」]{0,64}」，先把法言法语放一边，本条的核心意思可以概括成：\s*/

/** 条文开头的「第…条」（含之一等），用于条号单独成行、正文另起一行 */
const LEADING_ARTICLE_HEAD =
  /^(第[\s\u3000]*[一二三四五六七八九十百千万零〇两0-9]+[\s\u3000]*条(?:之一|之二|之三)?)\s*/

/**
 * 将「第一条……正文」拆成条号与正文，便于分层排版。
 * @returns {{ head: string, body: string }} 无条号前缀时 head 为空，body 为全文。
 */
export function splitLeadingArticleHeading(text) {
  if (text == null || text === '') return { head: '', body: '' }
  const s = String(text).trim()
  const m = s.match(LEADING_ARTICLE_HEAD)
  if (!m) return { head: '', body: s }
  const head = m[1].replace(/[\s\u3000]+/g, '')
  const rest = s.slice(m[0].length).trim()
  return { head, body: rest }
}

export function stripTechnicalNoiseFromInterpretation(text) {
  if (text == null || text === '') return ''
  let s = String(text)
  s = s.replace(/https?:\/\/\S+/gi, '')
  s = s.replace(/(?:\b[a-zA-Z]:\\|\/)prod\/\d{8}\/[^\s，。；、]+/gi, '')
  s = s.replace(/\S+\.(?:docx?|pdf|ofd)\b/gi, '')
  s = s.replace(/\bflk\.npc\.gov\.cn\S*/gi, '')
  s = s.replace(/\s{2,}/g, ' ').trim()
  return normalizeLawChineseInline(s)
}

export function stripNpcInterpretationDisclaimer(text) {
  if (text == null || text === '') return ''
  let s = String(text)
  const disclaimer =
    /(?:^|\r?\n)\s*来源：国家法律法规数据库页面爬取数据[，,]正式引用请以\s*flk\.npc\.gov\.cn\s*公布文本为准。\s*/gi
  s = s.replace(disclaimer, '\n')
  s = stripTechnicalNoiseFromInterpretation(s)
  return s.replace(/\n{3,}/g, '\n\n').trim()
}

/**
 * 与后端 LawInterpretationTexts.extractCoreInterpretation 对齐：正文区不展示历史拼接的「适用/例外/风险提示」等块。
 */
export function stripBundledInterpretationSections(text) {
  if (text == null || text === '') return ''
  let t = String(text).trim()
  const markers = ['\n\n适用：', '\n\n例外：', '\n\n风险提示：']
  let cut = -1
  for (const m of markers) {
    const i = t.indexOf(m)
    if (i >= 0 && (cut < 0 || i < cut)) cut = i
  }
  if (cut >= 0) t = t.slice(0, cut).trim()
  if (t.startsWith('要点：')) t = t.slice('要点：'.length).trim()
  while (t.startsWith('【大白话】')) t = t.slice('【大白话】'.length).trim()
  t = t.replace(LEGACY_DIGEST_LEAD_IN, '')
  return normalizeLawChineseInline(t.trim())
}

/**
 * 将模型或规则生成的 ⟦重点⟧ 转为下划线，换行转为 <br>（需配合 v-html，内容已转义）。
 */
export function interpretationBodyToHtml(text) {
  if (text == null || text === '') return ''
  const s = String(text)
  let out = ''
  let last = 0
  const re = /⟦([^⟧]*)⟧/g
  let m
  while ((m = re.exec(s)) !== null) {
    out += escapeHtml(s.slice(last, m.index))
    out += `<u class="interp-em">${escapeHtml(m[1])}</u>`
    last = m.index + m[0].length
  }
  out += escapeHtml(s.slice(last))
  return out.split(/\r?\n/).join('<br>')
}

/** 复制引用时去掉强调括号，避免剪贴板出现陌生符号 */
export function interpretationPlainForCopy(text) {
  if (text == null || text === '') return ''
  return String(text).replace(/⟦|⟧/g, '').trim()
}

const SHORT_INTERP_THRESH = 200

/**
 * 在通俗解读偏短时追加阅读说明（与入库摘要衔接，便于非专业人士阅读）。
 */
export function expandInterpretationGuide(law) {
  if (!law) return ''
  const raw = (law.lawName || '').trim()
  const bracket = raw.startsWith('《') && raw.endsWith('》') ? raw : `《${raw}》`
  const cat = law.category || '该领域'
  const status = law.status || ''
  const art = (law.articleNo || '').trim()
  const isFull = art === '全文' || art.includes('全文')

  const statusBit = status ? `列表所示时效性为「${status}」，适用时请关注是否另有修订决定或配套文件。` : ''

  if (isFull) {
    return (
      `【阅读说明】${bracket}以全文收录，适合先把握章节顺序与术语定义，再检索与您事项对应的条款。${statusBit}` +
      `从分类「${cat}」可大致判断其在法律体系中的层级。理解权利义务时，建议标注主体（谁）、行为（做什么）与后果（承担什么责任）三个要素。`
    )
  }
  return (
    `【阅读说明】本条出自${bracket}，实务中常与「${cat}」类争议相关。${statusBit}` +
    `可将条文拆成「要件—效果—例外」阅读：前半段多为适用条件，后半段多为法律效果；出现「但是」「除外」等用语时需结合例外区块一并理解。下列摘要仅辅助入门，个案仍应以条文全文及证据为基础。`
  )
}

/** 是否需要在解读后追加加长说明 */
export function shouldAppendInterpretationGuide(strippedInterpretation) {
  const t = (strippedInterpretation || '').trim()
  return t.length === 0 || t.length < SHORT_INTERP_THRESH
}

export const LAW_EXCEPTION_EMPTY =
  '未单独列举例外情形。是否适用本条需结合特别规定、合同约定及个案事实综合判断。'

export function escapeHtml(s) {
  if (s == null || s === '') return ''
  return String(s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

const URL_IN_TEXT =
  /https?:\/\/[^\s<>"{}|\\^`[\]()]+/gi

/**
 * 将纯文本中的 http(s) 转为可点击链接（先转义再替换，避免 XSS）。
 */
export function linkifyPlainText(text) {
  if (text == null || text === '') return ''
  const str = String(text)
  let out = ''
  let last = 0
  let m
  const re = new RegExp(URL_IN_TEXT.source, 'gi')
  while ((m = re.exec(str)) !== null) {
    out += escapeHtml(str.slice(last, m.index))
    const url = m[0].replace(/[),.;]+$/g, '')
    const trailing = m[0].slice(url.length)
    const safeHref = escapeHtml(url)
    out += `<a href="${safeHref}" target="_blank" rel="noopener noreferrer">${safeHref}</a>`
    out += escapeHtml(trailing)
    last = m.index + m[0].length
  }
  out += escapeHtml(str.slice(last))
  return out
}

/** 法条正文：保留换行并链化 URL */
export function formatLawBodyHtml(text) {
  if (text == null || text === '') return ''
  return String(text)
    .split(/\r?\n/)
    .map((line) => linkifyPlainText(line))
    .join('<br>')
}

export function formatLawDate(d) {
  if (d == null || d === '') return ''
  const s = typeof d === 'string' ? d : String(d)
  return s.length >= 10 ? s.slice(0, 10) : s
}

export function isHiddenSourceUrl(url) {
  return !url || String(url).startsWith('seed://')
}

/** 详情页节选上限（字符） */
export const CONTENT_EXCERPT_DETAIL_MAX = 720
/** 检索列表卡片节选上限 */
export const CONTENT_EXCERPT_LIST_MAX = 380

/**
 * 截取正文前面一段作节选，尽量在句号、问号、叹号或换行处截断。
 * @returns {{ excerpt: string, truncated: boolean, fullLength: number }}
 */
export function excerptLawContent(text, maxChars = CONTENT_EXCERPT_DETAIL_MAX) {
  if (text == null || text === '') {
    return { excerpt: '', truncated: false, fullLength: 0 }
  }
  const full = normalizeLawChineseInline(String(text).trim())
  const fullLength = full.length
  const n = Math.max(120, Number(maxChars) || CONTENT_EXCERPT_DETAIL_MAX)
  if (fullLength <= n) {
    return { excerpt: full, truncated: false, fullLength }
  }
  let chunk = full.slice(0, n)
  let cut = -1
  for (const p of ['。', '！', '？', '\n']) {
    const i = chunk.lastIndexOf(p)
    if (i > n * 0.42) {
      cut = Math.max(cut, i)
    }
  }
  if (cut >= 0) {
    chunk = full.slice(0, cut + 1).trim()
  } else {
    chunk = chunk.trimEnd() + '…'
  }
  return { excerpt: chunk, truncated: true, fullLength }
}
