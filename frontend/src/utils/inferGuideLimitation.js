/** 与后端 GuideTemplateView.limitationType 一致，用于接口未返回前回退 */
const FLOW_TYPE_TO_LIMITATION = {
  'labor-arbitration': '劳动仲裁',
  'civil-lawsuit': '一般民事',
  'loan-collection': '民间借贷',
  'lease-deposit': '租赁押金',
  'work-injury': '工伤认定',
  'divorce-lawsuit': '一般民事',
  'consumer-complaint': '一般民事',
  'contract-termination': '一般民事',
}

/**
 * 根据维权流程模板与案情文本推断用于时效计算的纠纷类型（与后端 LimitationService 口径一致）。
 * @param {object} opts
 * @param {string} opts.flowType 模板 type，或 'custom'
 * @param {string} opts.customFlow
 * @param {string} opts.facts
 * @param {{ limitationType?: string } | null} opts.template 当前选中的内置模板（来自接口）
 */
export function inferGuideLimitationType({ flowType, customFlow, facts, template }) {
  const text = `${customFlow || ''}\n${facts || ''}`.trim()
  const inferred = inferFromCaseText(text)

  if (flowType === 'custom') {
    return { type: inferred, source: text.length >= 4 ? '案情描述' : '默认（案情过短）' }
  }

  const base = template?.limitationType || FLOW_TYPE_TO_LIMITATION[flowType] || '一般民事'

  if (!text || text.length < 6) {
    return { type: base, source: '流程模板' }
  }

  if (base !== '一般民事') {
    if (conflictsWithTemplate(base, text)) {
      return { type: inferred, source: '案情描述（与模板不完全一致，已按案情修正时效口径）' }
    }
    return { type: base, source: '流程模板' }
  }

  if (inferred !== '一般民事') {
    return { type: inferred, source: '案情描述（在一般民事流程下进一步细分）' }
  }

  return { type: base, source: '流程模板' }
}

function inferFromCaseText(raw) {
  const s = raw.replace(/\s+/g, '')
  if (!s) return '一般民事'

  if (/工伤|职业病|事故伤害|劳动能力鉴定|认定工伤/.test(s)) return '工伤认定'
  if (
    /劳动仲裁|拖欠工资|加班费|经济补偿|违法辞退|二倍工资|未签劳动合同|劳动关系|社保|用人单位|劳动合同|辞退|克扣工资|中介扣工资|暑期工|实习/.test(s)
  ) {
    return '劳动仲裁'
  }
  if (/借条|民间借贷|出借|借款人|欠款|借款本金|利息|还款期限|网贷/.test(s)) return '民间借贷'
  if (/押金|租房|房东|租客|租赁|退租|物业|装修押金/.test(s)) return '租赁押金'
  return '一般民事'
}

function conflictsWithTemplate(base, text) {
  const inferred = inferFromCaseText(text)
  if (inferred === base) return false
  if (base === '劳动仲裁') {
    const looksLoan = /借条|出借人|借款人|借款本金|利息约定/.test(text)
    const looksLabor = /劳动|工资|辞退|合同|仲裁|加班|社保/.test(text)
    return looksLoan && !looksLabor
  }
  if (base === '民间借贷') {
    const looksLabor = /劳动仲裁|辞退|经济补偿|劳动关系/.test(text)
    return looksLabor
  }
  if (base === '工伤认定') {
    return inferred === '劳动仲裁' && /拖欠工资|辞退|未缴社保/.test(text) && !/工伤|事故|职业病/.test(text)
  }
  return false
}

export function limitationTypeDisplayName(type) {
  const map = {
    劳动仲裁: '劳动仲裁（申请仲裁：通常为 1 年）',
    工伤认定: '工伤认定（个人申请：通常为 1 年）',
    民间借贷: '民间借贷（诉讼时效：通常为 3 年）',
    租赁押金: '租赁/押金（诉讼时效：通常为 3 年）',
    一般民事: '一般民事（诉讼时效：通常为 3 年）',
  }
  return map[type] || map['一般民事']
}
