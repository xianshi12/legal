<script setup>
import AppLayout from '../../components/common/AppLayout.vue'
import { computed, onMounted, reactive, ref } from 'vue'
import { getCaseMeta, matchCases } from '../../api/case'

const loading = ref(false)
const error = ref('')
const response = ref({ total: 0, items: [], vectorEnabled: false })
/** 用户是否已点击/触发过检索（用于首屏无默认查询时的提示文案） */
const hasRunSearch = ref(false)
const meta = ref({
  regions: ['北京', '上海', '广东', '浙江', '江苏'],
  yearRanges: ['全部', '近一年', '近三年', '近五年', '2024年', '2023年', '2022年', '2021年及以前'],
  courtLevels: ['基层人民法院', '中级人民法院', '高级人民法院'],
  total: 0,
  vectorEnabled: false,
  embeddingModel: 'text-embedding-v4',
})
const form = reactive({
  q: '',
  region: '全国',
  yearRange: '全部',
  courtLevel: '全部',
  page: 1,
  size: 10,
})

const caseList = computed(() =>
  response.value.items.map((item) => ({
    id: item.id,
    type: item.caseType || item.cause,
    trial: item.trialLevel,
    year: item.judgmentYear,
    title: item.title,
    meta: `案号：${item.caseNo} | ${item.courtName}`,
    similarity: `${Math.round((item.similarity || 0) * 100)}%`,
    similarityLabel: item.similarityLabel,
    points: item.adjudicationPoints,
    judgment: item.judgmentResult,
    evidence: item.keyEvidence || [],
    strong: (item.similarity || 0) >= 0.82,
  })),
)

async function loadMeta() {
  try {
    meta.value = { ...meta.value, ...(await getCaseMeta()) }
  } catch (e) {
    error.value = e.message
  }
}

async function runSearch() {
  hasRunSearch.value = true
  loading.value = true
  error.value = ''
  try {
    response.value = await matchCases(form)
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await loadMeta()
  if (form.q?.trim()) {
    await runSearch()
  } else {
    response.value = {
      ...response.value,
      vectorEnabled: !!meta.value.vectorEnabled,
    }
  }
})
</script>

<template>
  <AppLayout>
    <div class="page">
      <div class="wrap">
        <div class="crumbs">
          <a class="link" href="#" @click.prevent>主页</a>
          <span class="material-symbols-outlined chev">chevron_right</span>
          <span class="here">案例匹配</span>
        </div>

        <div class="hero">
          <div class="heroInner">
            <h1 class="h1">智能案例匹配检索</h1>
            <p class="sub">输入案情描述，匹配相似判例与裁判要点。</p>
          </div>
          <div class="heroSearch">
            <span class="material-symbols-outlined heroIcon">search</span>
            <input
              v-model.trim="form.q"
              class="heroInput"
              placeholder="例如：房东提前解约不退押金，租客如何主张违约责任？"
              @keyup.enter="runSearch"
            />
            <button class="heroBtn" type="button" :disabled="loading" @click="runSearch">
              {{ loading ? '检索中' : '检索案例' }}
            </button>
          </div>
        </div>

        <div class="filters">
          <span class="filterTitle">
            <span class="material-symbols-outlined">tune</span>
            筛选:
          </span>
          <select v-model="form.region" class="filterBtn" @change="runSearch">
            <option value="全国">地区：全国</option>
            <option v-for="item in meta.regions" :key="item" :value="item">地区：{{ item }}</option>
          </select>
          <select v-model="form.yearRange" class="filterBtn" @change="runSearch">
            <option v-for="item in meta.yearRanges" :key="item" :value="item">判决时间：{{ item }}</option>
          </select>
          <select v-model="form.courtLevel" class="filterBtn" @change="runSearch">
            <option value="全部">法院级别：全部</option>
            <option v-for="item in meta.courtLevels" :key="item" :value="item">法院级别：{{ item }}</option>
          </select>
          <div class="total">
            为您找到 <strong>{{ response.total }}</strong> 个相似案例
          </div>
        </div>

        <div class="list">
          <div v-if="error" class="empty error">{{ error }}</div>
          <div v-else-if="loading" class="empty">正在匹配同类生效案例...</div>
          <div v-else-if="!caseList.length && !hasRunSearch" class="empty hint">
            请输入案情描述，点击「检索案例」或按回车开始匹配。
          </div>
          <div v-else-if="!caseList.length" class="empty">暂无匹配案例，请调整案情描述或筛选条件。</div>

          <div class="card" v-for="item in caseList" :key="item.id">
            <div :class="['leftBar', item.strong ? 'primary' : 'tertiary']"></div>
            <div class="cardHead">
              <div>
                <div class="chips">
                  <span class="chip type">{{ item.type }}</span>
                  <span class="chip">{{ item.trial }}</span>
                  <span class="chip">{{ item.year }}</span>
                </div>
                <h2>{{ item.title }}</h2>
                <p class="meta">{{ item.meta }}</p>
              </div>
              <div class="score">
                <div :class="['scoreNum', item.strong ? 'strong' : 'normal']">{{ item.similarity }}</div>
                <span>{{ item.similarityLabel }}</span>
              </div>
            </div>

            <div class="cardGrid">
              <div class="infoBlock">
                <h3>
                  <span class="material-symbols-outlined">gavel</span>
                  裁判要点
                </h3>
                <p>{{ item.points }}</p>
              </div>
              <div class="infoBlock">
                <h3>
                  <span class="material-symbols-outlined">assignment_turned_in</span>
                  判决结果
                </h3>
                <p>{{ item.judgment }}</p>
              </div>
              <div class="infoBlock">
                <h3>
                  <span class="material-symbols-outlined">policy</span>
                  胜诉关键证据
                </h3>
                <ul>
                  <li v-for="ev in item.evidence" :key="ev">{{ ev }}</li>
                </ul>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </AppLayout>
</template>

<style scoped>
.page { height: 100%; overflow: auto; }
.wrap { max-width: 1280px; margin: 0 auto; padding: 24px; }
.crumbs { display: flex; align-items: center; gap: 8px; margin-bottom: 16px; font-size: 14px; color: var(--on-surface-variant); }
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
.heroInner { text-align: center; max-width: 760px; margin: 0 auto 16px; }
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
}
.heroInput {
  width: 100%;
  height: 56px;
  border: 1px solid var(--outline-variant);
  border-radius: 12px;
  background: var(--surface);
  padding: 0 126px 0 48px;
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
  padding: 0 18px;
  background: var(--primary);
  color: var(--on-primary);
  font-weight: 600;
  cursor: pointer;
}
.heroBtn:hover { background: var(--tertiary); }
.heroBtn:disabled { opacity: .65; cursor: wait; }

.filters {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 32px;
  padding: 8px;
  border-radius: 8px;
  border: 1px solid var(--surface-variant);
  background: var(--surface-container-low);
}
.filterTitle {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-right: 6px;
  font-size: 14px;
  color: var(--on-surface-variant);
  font-weight: 500;
}
.filterTitle .material-symbols-outlined { font-size: 16px; }
.filterBtn {
  height: 34px;
  border: 1px solid var(--outline-variant);
  border-radius: 8px;
  padding: 0 10px;
  background: var(--surface-container-lowest);
  color: var(--on-surface);
  font-size: 14px;
  display: flex;
  align-items: center;
  gap: 2px;
  cursor: pointer;
  outline: none;
}
.filterBtn:hover { border-color: var(--primary); }
.total {
  margin-left: auto;
  font-size: 14px;
  color: var(--on-surface-variant);
}
.total strong { color: var(--primary); }

.list { display: flex; flex-direction: column; gap: 16px; }
.empty {
  border: 1px solid var(--surface-variant);
  border-radius: 12px;
  background: var(--surface-container-lowest);
  box-shadow: var(--shadow-sm);
  padding: 18px;
  color: var(--on-surface-variant);
}
.empty.error {
  background: var(--error-container);
  color: var(--on-error-container);
  border-color: transparent;
}
.card {
  position: relative;
  overflow: hidden;
  border: 1px solid var(--surface-variant);
  border-radius: 12px;
  background: var(--surface-container-lowest);
  box-shadow: var(--shadow-sm);
  transition: box-shadow 0.2s;
  padding: 16px;
}
.card:hover { box-shadow: var(--shadow-md); }
.leftBar {
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 4px;
}
.leftBar.primary { background: var(--primary); }
.leftBar.tertiary { background: var(--tertiary); }

.cardHead {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  padding-left: 8px;
}
.chips { display: flex; gap: 8px; margin-bottom: 8px; flex-wrap: wrap; }
.chip {
  font-size: 12px;
  line-height: 1;
  padding: 6px 8px;
  border-radius: 6px;
  color: var(--on-surface-variant);
  background: var(--surface-container);
}
.chip.type {
  background: var(--primary-container);
  color: var(--primary);
  font-weight: 600;
}
h2 {
  margin: 0;
  font-size: 24px;
  line-height: 1.4;
  color: var(--on-surface);
}
.meta { margin: 4px 0 0; font-size: 14px; color: var(--on-surface-variant); }
.score { display: flex; flex-direction: column; align-items: flex-end; }
.scoreNum {
  border-radius: 8px;
  padding: 4px 10px;
  font-size: 24px;
  font-weight: 700;
  line-height: 1.2;
}
.scoreNum.strong {
  background: var(--secondary-container);
  color: var(--on-secondary-container);
}
.scoreNum.normal {
  background: var(--surface-container-high);
  color: var(--on-surface);
}
.score span { margin-top: 4px; font-size: 14px; color: var(--on-surface-variant); }

.cardGrid {
  margin-top: 16px;
  border-top: 1px solid var(--surface-variant);
  padding-top: 16px;
  padding-left: 8px;
  display: grid;
  grid-template-columns: 1fr;
  gap: 24px;
}
@media (min-width: 960px) {
  .cardGrid { grid-template-columns: repeat(3, minmax(0, 1fr)); }
}
.infoBlock {
  border: 1px solid color-mix(in srgb, var(--surface-variant) 50%, transparent);
  border-radius: 8px;
  background: var(--surface);
  padding: 12px;
}
.infoBlock h3 {
  margin: 0 0 8px;
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 16px;
  color: var(--on-surface);
}
.infoBlock h3 .material-symbols-outlined { font-size: 16px; color: var(--primary); }
.infoBlock p {
  margin: 0;
  font-size: 14px;
  line-height: 1.7;
  color: var(--on-surface-variant);
}
.infoBlock ul {
  margin: 0;
  padding-left: 18px;
  font-size: 14px;
  line-height: 1.7;
  color: var(--on-surface-variant);
}
</style>
