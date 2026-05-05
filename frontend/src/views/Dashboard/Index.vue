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
    <div class="wrap">
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
          class="card"
          shadow="hover"
          @click="router.push(c.path)"
        >
          <div class="card-title">{{ c.title }}</div>
          <div class="card-desc">{{ c.desc }}</div>
        </el-card>
      </div>
    </div>
  </AppLayout>
</template>

<style scoped>
.wrap {
  max-width: 1280px;
  margin: 0 auto;
}
.hero {
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 14px;
  padding: 26px;
  margin-bottom: 16px;
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
  gap: 14px;
}
.card {
  cursor: pointer;
  border-radius: 14px;
}
.card-title {
  font-weight: 800;
  margin-bottom: 6px;
}
.card-desc {
  color: #6b7280;
  line-height: 1.6;
  font-size: 14px;
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
}
</style>
