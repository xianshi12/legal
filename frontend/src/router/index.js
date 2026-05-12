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
import LoginRegister from '../views/Auth/LoginRegister.vue'
import ForgotPassword from '../views/Auth/ForgotPassword.vue'
import { useAuthStore } from '../stores/auth'

const routes = [
  { path: '/', redirect: '/login' },
  { path: '/login', component: LoginRegister, meta: { public: true } },
  { path: '/forgot-password', component: ForgotPassword, meta: { public: true } },
  { path: '/dashboard', component: Dashboard, meta: { requiresAuth: true } },
  { path: '/chat', component: Chat, meta: { requiresAuth: true } },
  { path: '/law', component: LawSearch, meta: { requiresAuth: true } },
  { path: '/law/:id', component: LawDetail, meta: { requiresAuth: true } },
  { path: '/document', component: DocumentIndex, meta: { requiresAuth: true } },
  { path: '/contract', component: ContractIndex, meta: { requiresAuth: true } },
  { path: '/case', component: CaseIndex, meta: { requiresAuth: true } },
  { path: '/guide', component: GuideIndex, meta: { requiresAuth: true } },
  { path: '/rag', component: RagIndex, meta: { requiresAuth: true } },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach((to) => {
  const auth = useAuthStore()
  const requiresAuth = to.matched.some((record) => record.meta.requiresAuth)
  const isPublic = to.matched.some((record) => record.meta.public)

  if (requiresAuth && !auth.isAuthenticated) {
    return {
      path: '/login',
      query: { redirect: to.fullPath },
    }
  }

  if (to.path === '/login' && isPublic && auth.isAuthenticated) {
    return '/dashboard'
  }

  if (requiresAuth && !auth.canAccess(to)) {
    return '/dashboard'
  }

  return true
})

export default router

