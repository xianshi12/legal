<script setup>
import AppLayout from '../../components/common/AppLayout.vue'
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { calculateLimitation, generateGuide, getGuideTemplates } from '../../api/guide'
import { inferGuideLimitationType, limitationTypeDisplayName } from '../../utils/inferGuideLimitation'

const router = useRouter()
const loading = ref(false)
const calcLoading = ref(false)
const error = ref('')
const templates = ref([])
const result = ref(null)
const form = reactive({
  flowType: 'labor-arbitration',
  customFlow: '',
  facts: '',
  incidentDate: new Date().toISOString().slice(0, 10),
})

const currentTemplate = computed(() => templates.value.find((item) => item.type === form.flowType))
const resolvedLimitation = computed(() =>
  inferGuideLimitationType({
    flowType: form.flowType,
    customFlow: form.customFlow,
    facts: form.facts,
    template: currentTemplate.value,
  }),
)

const timeline = computed(() => result.value?.steps || [])
const materials = computed(() => result.value?.materials || [])
const attentionPoints = computed(() => result.value?.attentionPoints || [])
const evidenceChecklist = computed(() => result.value?.evidenceChecklist || [])
const limitation = computed(() => result.value?.limitation)

const limitationStale = computed(() => {
  const lim = limitation.value
  if (!lim?.limitationType) return false
  return lim.limitationType !== resolvedLimitation.value.type
})

async function loadTemplates() {
  try {
    templates.value = await getGuideTemplates()
  } catch (e) {
    error.value = e.message
  }
}

function guidePayload() {
  return {
    flowType: form.flowType,
    customFlow: form.customFlow,
    facts: form.facts,
    incidentDate: form.incidentDate,
    limitationType: resolvedLimitation.value.type,
  }
}

async function runGuide() {
  loading.value = true
  error.value = ''
  try {
    result.value = await generateGuide(guidePayload())
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

async function runLimitation() {
  calcLoading.value = true
  error.value = ''
  try {
    const data = await calculateLimitation({
      limitationType: resolvedLimitation.value.type,
      incidentDate: form.incidentDate,
    })
    if (result.value) {
      result.value = { ...result.value, limitation: data }
    } else {
      result.value = await generateGuide(guidePayload())
    }
  } catch (e) {
    error.value = e.message
  } finally {
    calcLoading.value = false
  }
}

function copyMaterials() {
  const lines = ['【材料清单】', ...materials.value, '', '【证据清单】', ...evidenceChecklist.value]
  const text = lines.filter(Boolean).join('\n')
  if (text) navigator.clipboard?.writeText(text)
}

function selectFlow(type) {
  form.flowType = type
  if (type !== 'custom') form.customFlow = ''
  runGuide()
}

onMounted(async () => {
  await loadTemplates()
  await runGuide()
})
</script>

<template>
  <AppLayout>
    <div class="page custom-scrollbar">
      <div class="wrap">
        <div class="crumbs" aria-label="面包屑">
          <a class="link" href="#" @click.prevent="router.push('/dashboard')">主页</a>
          <span class="material-symbols-outlined chev">chevron_right</span>
          <span class="here">流程指引</span>
        </div>

        <div class="hero">
          <div class="heroInner">
            <h1 class="h1">维权流程与时效指引</h1>
            <p class="sub">
              分步梳理劳动仲裁、民事诉讼等常见路径：材料清单、时间节点、证据要点与风险提示；并可按权利受侵害之日测算仲裁或诉讼时效，降低错过期限的操作成本。
            </p>
          </div>
        </div>

        <section class="topCards" aria-label="流程、案情与时效">
          <article class="card card--accent-primary">
            <div class="card-head">
              <div class="icon-wrap icon-wrap--primary">
                <span class="material-symbols-outlined">account_tree</span>
              </div>
              <span class="pill">流程</span>
            </div>
            <h2 class="card-title">维权流程</h2>
            <p class="card-desc">
              选择劳动仲裁、民事起诉等模板，查看各阶段材料与节点；选「自定义流程」可结合案情由系统生成路径（需配置模型时支持 AI）。
            </p>
            <label class="field-label">流程模板</label>
            <select v-model="form.flowType" class="control" aria-label="流程类型" @change="selectFlow(form.flowType)">
              <option v-for="item in templates" :key="item.type" :value="item.type">{{ item.name }}</option>
              <option value="custom">自定义流程</option>
            </select>
            <div class="flowChips" role="group" aria-label="快捷选择">
              <button
                v-for="item in templates"
                :key="'chip-' + item.type"
                type="button"
                class="chipBtn"
                :class="{ active: form.flowType === item.type }"
                @click="selectFlow(item.type)"
              >
                {{ item.name }}
              </button>
            </div>
            <label v-if="form.flowType === 'custom'" class="field-label">自定义主题</label>
            <textarea
              v-if="form.flowType === 'custom'"
              v-model.trim="form.customFlow"
              class="control textarea"
              rows="2"
              placeholder="例如：物业扣装修押金不退，如何维权"
            />
          </article>

          <article class="card card--accent-tertiary">
            <div class="card-head">
              <div class="icon-wrap icon-wrap--tertiary">
                <span class="material-symbols-outlined">edit_note</span>
              </div>
              <span class="pill">案情</span>
            </div>
            <h2 class="card-title">案情与生成</h2>
            <p class="card-desc">
              补充时间线、金额、对方主体等事实，生成更贴合的分步说明与证据清单；不涉及自动法律结论，仅供参考。
            </p>
            <label class="field-label">案情描述（选填）</label>
            <textarea
              v-model.trim="form.facts"
              class="control textarea"
              rows="4"
              placeholder="例：2024年3月被辞退，未付经济补偿，有劳动合同与工资流水…"
            />
            <button class="primaryBtn" type="button" :disabled="loading" @click="runGuide">
              {{ loading ? '生成中…' : '生成流程指引' }}
            </button>
          </article>

          <article class="card card--accent-warn">
            <div class="card-head">
              <div class="icon-wrap icon-wrap--warn">
                <span class="material-symbols-outlined">calculate</span>
              </div>
              <span class="pill">时效</span>
            </div>
            <h2 class="card-title">时效计算</h2>
            <p class="card-desc">
              系统根据左侧「流程模板」与「案情描述」自动识别仲裁或诉讼时效类型（如劳动仲裁多为 1 年、民间借贷多为 3
              年），无需手选。起算日以您填写的「权利受侵害之日」为基准；复杂起算点请以实务认定为准。
            </p>
            <div class="inferredBox">
              <span class="field-label">自动识别的时效类型</span>
              <p class="inferredMain">{{ limitationTypeDisplayName(resolvedLimitation.type) }}</p>
              <p class="inferredSub">识别依据：{{ resolvedLimitation.source }}</p>
            </div>
            <p v-if="limitationStale" class="staleHint" role="status">
              流程或案情已调整，下方结果可能未同步。请点击「计算时效」或重新「生成流程指引」更新。
            </p>
            <label class="field-label">权利受侵害之日（起算）</label>
            <input v-model="form.incidentDate" class="control" type="date" />
            <button class="secondaryBtn" type="button" :disabled="calcLoading" @click="runLimitation">
              {{ calcLoading ? '计算中…' : '计算时效' }}
            </button>
            <div v-if="limitation" class="limitCard">
              <div class="limitRow">
                <span class="limitLabel">规则</span>
                <strong>{{ limitation.ruleSummary || '—' }}</strong>
              </div>
              <div class="limitRow">
                <span class="limitLabel">状态</span>
                <strong :class="{ warn: limitation.status !== '时效内' }">{{ limitation.status }}</strong>
              </div>
              <div class="limitRow">
                <span class="limitLabel">届满日</span>
                <span>{{ limitation.deadline }}</span>
              </div>
              <div class="limitRow">
                <span class="limitLabel">剩余</span>
                <span>{{ limitation.daysLeft }} 天</span>
              </div>
            </div>
          </article>
        </section>

        <div v-if="error" class="errorBanner" role="alert">{{ error }}</div>

        <section class="results" aria-label="生成结果">
          <header class="resultHead">
            <div class="resultTag">
              <span class="material-symbols-outlined fill">info</span>
              {{ result?.category || currentTemplate?.category || '流程指引' }}
            </div>
            <h2 class="resultTitle">{{ result?.title || currentTemplate?.name || '流程指引' }}</h2>
            <p class="resultSummary">
              {{
                result?.summary ||
                '选择流程并点击生成，或调整左侧卡片中的时效类型后计算，即可查看分步说明、材料与证据清单。'
              }}
            </p>
          </header>

          <div class="detailGrid">
            <div class="panelCard timelineCard">
              <h3 class="panelTitle">
                <span class="material-symbols-outlined">route</span>
                分步说明与时间节点
              </h3>
              <div class="timeline">
                <div v-for="item in timeline" :key="item.name" class="step">
                  <div :class="['dot', item.active ? 'active' : '', item.warning ? 'warning' : '']"></div>
                  <div class="stepBody">
                    <div class="stepHead">
                      <h4>{{ item.name }}</h4>
                      <span :class="['timeTag', item.warning ? 'warning' : '']">{{ item.time }}</span>
                    </div>
                    <p>{{ item.desc }}</p>
                  </div>
                </div>
              </div>
            </div>

            <div class="panelCard">
              <h3 class="panelTitle">
                <span class="material-symbols-outlined">folder_open</span>
                需准备的材料
              </h3>
              <ul class="materialList">
                <li v-for="item in materials" :key="item">
                  <span class="material-symbols-outlined">check_circle</span>
                  <span>{{ item }}</span>
                </li>
              </ul>
              <button class="ghostBtn" type="button" @click="copyMaterials">
                <span class="material-symbols-outlined">content_copy</span>
                复制材料与证据清单
              </button>
            </div>

            <div class="panelCard">
              <h3 class="panelTitle">
                <span class="material-symbols-outlined">fact_check</span>
                证据清单（仲裁 / 诉讼常用）
              </h3>
              <ul class="materialList">
                <li v-for="item in evidenceChecklist" :key="'ev-' + item">
                  <span class="material-symbols-outlined">bookmark</span>
                  <span>{{ item }}</span>
                </li>
              </ul>
              <p v-if="!evidenceChecklist.length" class="muted">生成流程后将展示该类纠纷常见证据项。</p>
            </div>

            <div class="panelCard warnPanel">
              <h3 class="panelTitle">
                <span class="material-symbols-outlined fill">warning</span>
                注意事项与时效
              </h3>
              <div class="warnBox">
                <strong>时效提示</strong>
                <p>
                  {{
                    limitation?.note ||
                    '请在上方「时效计算」卡片中选择类型并填写起算日；结果仅供参考，不构成法律意见。'
                  }}
                </p>
                <p v-if="limitation" class="limitDetail">
                  {{ limitation.ruleSummary }}；起算 {{ limitation.startDate }}，届满 {{ limitation.deadline }}，当前
                  <strong>{{ limitation.status }}</strong>。
                </p>
              </div>
              <p v-for="(item, idx) in attentionPoints" :key="'att-' + idx" class="attLine">
                <strong>注意：</strong>
                {{ item }}
              </p>
            </div>
          </div>
        </section>
      </div>
    </div>
  </AppLayout>
</template>

<style scoped>
.page {
  height: 100%;
  overflow: auto;
}
.wrap {
  max-width: 1280px;
  margin: 0 auto;
  padding: 24px;
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
  color: var(--on-surface-variant);
  line-height: 1.7;
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
  border-radius: var(--radius-lg, 12px);
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
  border-radius: var(--radius-lg, 12px);
}
.icon-wrap--primary {
  background: color-mix(in srgb, var(--secondary-container) 50%, transparent);
  color: var(--primary);
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
  min-height: 40px;
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

.flowChips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 4px;
}
.chipBtn {
  font-size: 12px;
  padding: 6px 10px;
  border-radius: 999px;
  border: 1px solid var(--outline-variant);
  background: var(--surface);
  color: var(--on-surface-variant);
  cursor: pointer;
}
.chipBtn:hover {
  background: var(--surface-container);
}
.chipBtn.active {
  border-color: var(--primary);
  background: var(--primary-container);
  color: var(--on-primary-container);
  font-weight: 600;
}

.primaryBtn {
  width: 100%;
  height: 44px;
  border: none;
  border-radius: 8px;
  background: var(--primary);
  color: var(--on-primary);
  font-weight: 600;
  cursor: pointer;
}
.primaryBtn:hover {
  filter: brightness(1.05);
}
.primaryBtn:disabled {
  opacity: 0.65;
  cursor: wait;
}

.secondaryBtn {
  width: 100%;
  height: 40px;
  border: 1px solid var(--outline-variant);
  border-radius: 8px;
  background: var(--surface-container-high);
  color: var(--primary);
  font-weight: 600;
  cursor: pointer;
}
.secondaryBtn:disabled {
  opacity: 0.65;
  cursor: wait;
}

.inferredBox {
  margin-bottom: 12px;
  padding: 12px;
  border-radius: 8px;
  background: var(--surface-container);
  border: 1px solid var(--outline-variant);
}
.inferredMain {
  margin: 0 0 6px;
  font-size: 15px;
  font-weight: 600;
  color: var(--on-surface);
  line-height: 1.5;
}
.inferredSub {
  margin: 0;
  font-size: 12px;
  line-height: 1.5;
  color: var(--on-surface-variant);
}
.staleHint {
  margin: 0 0 12px;
  padding: 8px 10px;
  border-radius: 6px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--on-tertiary-container);
  background: var(--tertiary-container);
}

.limitCard {
  margin-top: 12px;
  padding: 12px;
  border-radius: 8px;
  background: var(--surface-container);
  display: grid;
  gap: 8px;
  font-size: 13px;
  color: var(--on-surface-variant);
}
.limitRow {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 8px;
}
.limitLabel {
  min-width: 3em;
  color: var(--outline);
  font-size: 12px;
}
.limitRow strong.warn {
  color: var(--error);
}

.errorBanner {
  margin-bottom: 20px;
  padding: 12px 16px;
  border-radius: 8px;
  background: var(--error-container);
  color: var(--on-error-container);
  font-size: 14px;
}

.results {
  margin-bottom: 32px;
}
.resultHead {
  margin-bottom: 20px;
}
.resultTag {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 30px;
  border-radius: 999px;
  padding: 0 12px;
  background: var(--secondary-container);
  color: var(--on-secondary-container);
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.04em;
}
.resultTag .material-symbols-outlined {
  font-size: 16px;
}
.resultTitle {
  margin: 12px 0 8px;
  font-size: 28px;
  line-height: 1.25;
  color: var(--on-surface);
}
.resultSummary {
  margin: 0;
  max-width: 900px;
  font-size: 16px;
  line-height: 1.65;
  color: var(--on-surface-variant);
}

.detailGrid {
  display: grid;
  grid-template-columns: 1fr;
  gap: 20px;
}
@media (min-width: 900px) {
  .detailGrid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .timelineCard {
    grid-column: span 2;
  }
}

.panelCard {
  background: var(--surface-container-lowest);
  border: 1px solid var(--surface-container-highest);
  border-radius: 12px;
  box-shadow: var(--shadow-sm);
  padding: 22px;
}
.panelTitle {
  margin: 0 0 16px;
  font-size: 18px;
  font-weight: 600;
  color: var(--on-surface);
  display: flex;
  align-items: center;
  gap: 8px;
}
.panelTitle .material-symbols-outlined {
  color: var(--primary);
}

.timeline {
  position: relative;
  margin-left: 8px;
  border-left: 2px solid var(--surface-container-high);
  padding-left: 18px;
  display: flex;
  flex-direction: column;
  gap: 22px;
}
.step {
  position: relative;
}
.dot {
  position: absolute;
  left: -27px;
  top: 4px;
  width: 14px;
  height: 14px;
  border-radius: 999px;
  background: var(--surface-container-highest);
  border: 3px solid var(--surface-container-lowest);
}
.dot.active {
  background: var(--primary);
}
.dot.warning {
  background: var(--error);
}
.stepHead {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 10px;
}
.stepHead h4 {
  margin: 0;
  font-size: 16px;
  color: var(--on-surface);
}
.timeTag {
  height: 24px;
  border-radius: 6px;
  background: var(--surface-variant);
  color: var(--on-surface-variant);
  font-size: 12px;
  font-weight: 600;
  padding: 0 8px;
  display: inline-flex;
  align-items: center;
}
.timeTag.warning {
  background: var(--error-container);
  color: var(--on-error-container);
}
.stepBody p {
  margin: 6px 0 0;
  font-size: 14px;
  line-height: 1.7;
  color: var(--on-surface-variant);
}

.materialList {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.materialList li {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  color: var(--on-surface-variant);
  font-size: 14px;
  line-height: 1.6;
}
.materialList .material-symbols-outlined {
  margin-top: 2px;
  font-size: 18px;
  color: var(--outline);
}

.ghostBtn {
  margin-top: 14px;
  width: 100%;
  height: 40px;
  border: 1px solid var(--outline-variant);
  border-radius: 8px;
  background: var(--surface-container);
  color: var(--on-surface);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  font-weight: 600;
  cursor: pointer;
}
.ghostBtn:hover {
  background: var(--surface-container-high);
}

.muted {
  margin: 0;
  font-size: 14px;
  color: var(--on-surface-variant);
}

.warnPanel {
  border-left: 4px solid var(--error);
}
.warnPanel .panelTitle .material-symbols-outlined {
  color: var(--error);
}
.warnBox {
  border-radius: 8px;
  background: color-mix(in srgb, var(--error-container) 35%, transparent);
  padding: 12px;
  margin-bottom: 12px;
}
.warnBox strong {
  display: block;
  margin-bottom: 6px;
  color: var(--error);
}
.warnBox p,
.attLine {
  margin: 0;
  font-size: 14px;
  line-height: 1.7;
  color: var(--on-surface-variant);
}
.limitDetail {
  margin-top: 8px !important;
}
.attLine + .attLine {
  margin-top: 10px;
}
.attLine strong {
  color: var(--on-surface);
}
</style>
