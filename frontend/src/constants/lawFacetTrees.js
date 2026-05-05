/**
 * 法条检索 — 筛选树数据（叶节点 filterValue 参与后端 IN 查询，须与库内字段可匹配）。
 * 可与接口 /api/laws/meta 返回的分类等合并展示。
 */

export const TIMELINESS_OPTIONS = [
  { label: '尚未生效', value: '尚未生效' },
  { label: '有效', value: '现行有效' },
  { label: '已修改', value: '已修改' },
  { label: '已废止', value: '已废止' },
]

/** 法律法规分类（示例结构，叶节点 filterValue 对应 category 字段） */
export const LAW_CATEGORY_TREE_DATA = [
  { id: 'cat-xianfa', label: '宪法', filterValue: '宪法' },
  {
    id: 'cat-falv',
    label: '法律',
    children: [
      { id: 'cat-minfa', label: '民法', filterValue: '民法' },
      { id: 'cat-laodong', label: '劳动法', filterValue: '劳动法' },
      { id: 'cat-xingfa', label: '刑法', filterValue: '刑法' },
      { id: 'cat-xingzheng', label: '行政法', filterValue: '行政法' },
    ],
  },
  { id: 'cat-xzfg', label: '行政法规', filterValue: '行政法规' },
  { id: 'cat-jcfg', label: '监察法规', filterValue: '监察法规' },
  {
    id: 'cat-difang',
    label: '地方法规',
    children: [
      { id: 'cat-dfxfg', label: '地方性法规', filterValue: '地方法规' },
    ],
  },
  { id: 'cat-sfjs', label: '司法解释', filterValue: '司法解释' },
  { id: 'cat-zonghe', label: '综合', filterValue: '综合' },
]

/** 制定机关（叶节点 filterValue 对应 issuing_body） */
export const ISSUING_BODY_TREE_DATA = [
  {
    id: 'iss-npc',
    label: '全国人大及其常委会',
    children: [
      {
        id: 'iss-npc-sc',
        label: '全国人民代表大会常务委员会',
        filterValue: '全国人民代表大会常务委员会',
      },
    ],
  },
  { id: 'iss-gwy', label: '国务院', filterValue: '国务院' },
  { id: 'iss-jw', label: '国家监察委员会', filterValue: '国家监察委员会' },
  { id: 'iss-zgfy', label: '最高人民法院', filterValue: '最高人民法院' },
  { id: 'iss-zgjcy', label: '最高人民检察院', filterValue: '最高人民检察院' },
  {
    id: 'iss-local',
    label: '地方人大及其常委会',
    children: [
      {
        id: 'iss-local-1',
        label: '省级人大及其常委会（示例）',
        filterValue: '省、自治区、直辖市人民代表大会及其常务委员会',
      },
    ],
  },
]

/**
 * 将带 filterValue 的树拍平为下拉选项（label 带层级路径，value 为提交值）。
 * @param {Array<{ label: string, filterValue?: string, children?: unknown[] }>} nodes
 * @param {string} pathPrefix
 * @returns {{ label: string, value: string }[]}
 */
export function flattenFacetTree(nodes, pathPrefix = '') {
  const out = []
  if (!nodes?.length) return out
  for (const n of nodes) {
    const segment = n.label || ''
    const path = pathPrefix ? `${pathPrefix} / ${segment}` : segment
    if (n.filterValue) {
      out.push({ label: path, value: n.filterValue })
    }
    if (n.children?.length) {
      out.push(...flattenFacetTree(n.children, path))
    }
  }
  return out
}
