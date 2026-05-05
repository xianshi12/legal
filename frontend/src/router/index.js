import { createRouter, createWebHistory } from 'vue-router'
import Dashboard from '../views/Dashboard/Index.vue'
import Chat from '../views/Chat/Index.vue'
import StubPage from '../views/StubPage.vue'
import LawSearch from '../views/LawSearch/Index.vue'
import LawDetail from '../views/LawSearch/LawDetail.vue'
import DocumentIndex from '../views/Document/Index.vue'
import ContractIndex from '../views/Contract/Index.vue'
import CaseIndex from '../views/Case/Index.vue'
import GuideIndex from '../views/Guide/Index.vue'
import RagIndex from '../views/Rag/Index.vue'

const routes = [
  { path: '/', redirect: '/dashboard' },
  { path: '/dashboard', component: Dashboard },
  { path: '/chat', component: Chat },
  { path: '/law', component: LawSearch },
  { path: '/law/:id', component: LawDetail },
  { path: '/document', component: DocumentIndex },
  { path: '/contract', component: ContractIndex },
  { path: '/case', component: CaseIndex },
  { path: '/guide', component: GuideIndex },
  { path: '/rag', component: RagIndex },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

export default router

