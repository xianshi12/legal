<script setup>
import AppLayout from '../../components/common/AppLayout.vue'
import { useRouter } from 'vue-router'

const router = useRouter()

const cards = [
  { title: '智能咨询', desc: '法律问题问答与引用', path: '/chat' },
  { title: '法规检索', desc: '关键词、案由与条号检索', path: '/law' },
  { title: '文书生成', desc: '按模板生成法律文书', path: '/document' },
  { title: '合同审查', desc: '风险条款与修订建议', path: '/contract' },
  { title: '案例匹配', desc: '相似判例与裁判要点', path: '/case' },
  { title: '流程指引', desc: '维权步骤与材料清单', path: '/guide' },
  { title: 'RAG知识库', desc: '资料上传与检索', path: '/rag' },
]
</script>

<template>
  <AppLayout>
    <div class="dashboard">
      <div class="dashboard-inner">
        <div class="hero">
          <div class="tag">AI 智能法律助手</div>
          <h1>面向普通用户的法律服务平台</h1>
          <p>选择下方模块开始使用。</p>
          <div class="actions">
            <el-button type="primary" size="large" @click="router.push('/chat')">开始咨询</el-button>
          </div>
        </div>

        <div class="grid">
          <el-card
            v-for="c in cards"
            :key="c.title"
            class="module-card"
            shadow="never"
            @click="router.push(c.path)"
          >
            <div class="card-title">{{ c.title }}</div>
            <div class="card-desc">{{ c.desc }}</div>
          </el-card>
        </div>
      </div>
    </div>
  </AppLayout>
</template>

<style scoped>
.dashboard {
  width: 100%;
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  display: flex;
  justify-content: center;
  padding: 24px 24px 48px;
  box-sizing: border-box;
}

.dashboard-inner {
  width: 100%;
  max-width: 1080px;
}

.hero {
  background: linear-gradient(
    135deg,
    color-mix(in srgb, var(--primary) 14%, var(--surface-container-lowest)) 0%,
    var(--surface-container-lowest) 52%
  );
  border: 1px solid color-mix(in srgb, var(--primary) 18%, var(--outline-variant));
  border-radius: 14px;
  padding: 26px;
  margin-bottom: 20px;
  position: relative;
  overflow: hidden;
}

.hero::after {
  content: '';
  position: absolute;
  right: -120px;
  top: -120px;
  width: 320px;
  height: 320px;
  border-radius: 999px;
  background: rgba(0, 93, 172, 0.12);
  filter: blur(4px);
}

.tag {
  display: inline-flex;
  align-items: center;
  padding: 6px 10px;
  border-radius: 999px;
  background: rgba(25, 118, 210, 0.1);
  color: #005dac;
  font-size: 12px;
  font-weight: 700;
  margin-bottom: 10px;
}

h1 {
  margin: 0 0 6px 0;
  font-size: 28px;
  letter-spacing: -0.02em;
}

p {
  margin: 0;
  color: #6b7280;
}

.actions {
  margin-top: 14px;
}

.grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
}

.module-card {
  cursor: pointer;
  border-radius: 14px;
  border: 1px solid var(--outline-variant);
  background: var(--surface-container-lowest);
  transition:
    background-color 0.22s ease,
    border-color 0.22s ease,
    box-shadow 0.22s ease;
}

.module-card :deep(.el-card__body) {
  padding: 20px 18px;
}

.module-card:hover {
  background: color-mix(in srgb, var(--primary) 11%, var(--surface-container-lowest));
  border-color: color-mix(in srgb, var(--primary) 58%, var(--outline-variant));
  box-shadow:
    0 0 0 1px color-mix(in srgb, var(--primary) 42%, transparent),
    0 12px 32px rgba(0, 93, 172, 0.14);
}

.card-title {
  font-weight: 800;
  margin-bottom: 6px;
}

.card-desc {
  color: #6b7280;
  line-height: 1.6;
  font-size: 14px;
  transition: color 0.22s ease;
}

.module-card:hover .card-desc {
  color: var(--on-surface-variant);
}

@media (max-width: 1100px) {
  .grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 700px) {
  .grid {
    grid-template-columns: repeat(1, minmax(0, 1fr));
  }

  .dashboard {
    padding: 16px 16px 40px;
  }
}
</style>
