<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

const STORAGE_KEY = 'legalAssistant:sidenavExpanded'

const route = useRoute()
const router = useRouter()

const active = computed(() => route.path)

/** 默认收起侧栏；本地存有「展开」偏好时再展开 */
const sidenavExpanded = ref(false)

const menus = [
  { path: '/chat', label: '智能咨询', icon: 'chat_bubble', activeFill: true },
  { path: '/law', label: '法条检索', icon: 'account_balance' },
  { path: '/document', label: '文书生成', icon: 'description' },
  { path: '/contract', label: '合同审查', icon: 'fact_check' },
  { path: '/case', label: '案例匹配', icon: 'find_in_page' },
  { path: '/guide', label: '流程指引', icon: 'account_tree' },
  { path: '/rag', label: 'RAG知识库', icon: 'database' },
]

function go(path) {
  router.push(path)
}

function toggleSidenav() {
  sidenavExpanded.value = !sidenavExpanded.value
}

onMounted(() => {
  try {
    const v = localStorage.getItem(STORAGE_KEY)
    if (v === '1') sidenavExpanded.value = true
    else if (v === '0') sidenavExpanded.value = false
  } catch {
    /* ignore */
  }
})

watch(sidenavExpanded, (v) => {
  try {
    localStorage.setItem(STORAGE_KEY, v ? '1' : '0')
  } catch {
    /* ignore */
  }
})
</script>

<template>
  <div class="shell">
    <!-- TopNavBar -->
    <header class="topnav">
      <div class="brand" @click="go('/dashboard')">
        <span class="material-symbols-outlined fill" style="color: var(--primary)">gavel</span>
        <span class="brand-text">法通AI</span>
      </div>
      <div class="top-actions">
        <button class="icon-btn" type="button">
          <span class="material-symbols-outlined">notifications</span>
        </button>
        <div class="profile" role="button" tabindex="0">
          <div class="avatar">
            <span class="material-symbols-outlined" style="color: var(--on-primary)">person</span>
          </div>
          <span class="profile-text">个人中心</span>
        </div>
      </div>
    </header>

    <div class="body">
      <!-- SideNavBar -->
      <nav
        class="sidenav custom-scrollbar"
        :class="sidenavExpanded ? 'sidenav--expanded' : 'sidenav--collapsed'"
        :aria-label="sidenavExpanded ? '法律模块导航（已展开）' : '法律模块导航（已收起）'"
      >
        <div class="sidenav-head">
          <h2 class="sidenav-cap">法律模块</h2>
          <p class="sidenav-sub">仅供参考，不构成正式法律意见</p>
        </div>

        <div class="sidenav-list">
          <a
            v-for="m in menus"
            :key="m.path"
            :class="['nav-item', active === m.path ? 'active' : '']"
            :title="m.label"
            href="#"
            @click.prevent="go(m.path)"
          >
            <span
              :class="['material-symbols-outlined nav-icon', active === m.path && m.activeFill ? 'fill' : '']"
            >
              {{ m.icon }}
            </span>
            <span class="nav-label">{{ m.label }}</span>
          </a>
        </div>

        <div class="sidenav-footer">
          <button
            type="button"
            class="sidenav-toggle"
            :aria-expanded="sidenavExpanded"
            :title="sidenavExpanded ? '收起侧边栏' : '展开侧边栏'"
            @click="toggleSidenav"
          >
            <span class="material-symbols-outlined">{{
              sidenavExpanded ? 'keyboard_double_arrow_left' : 'keyboard_double_arrow_right'
            }}</span>
            <span class="sidenav-toggle-label">{{ sidenavExpanded ? '收起' : '展开' }}</span>
          </button>
        </div>
      </nav>

      <!-- Main -->
      <main class="canvas">
        <slot />
      </main>
    </div>
  </div>
</template>

<style scoped>
.shell {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: var(--background);
}
.topnav {
  height: 64px;
  flex: 0 0 auto;
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0 24px;
  background: var(--surface-container-lowest);
  border-bottom: 1px solid var(--outline-variant);
  box-shadow: var(--shadow-sm);
  z-index: 50;
}
.brand {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
}
.brand-text {
  font-weight: 700;
  font-size: 24px;
  line-height: 1.4;
  color: var(--primary);
  letter-spacing: -0.01em;
}
.top-actions {
  display: flex;
  align-items: center;
  gap: 16px;
}
.icon-btn {
  width: 40px;
  height: 40px;
  border: none;
  background: transparent;
  border-radius: 999px;
  color: var(--on-surface-variant);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: background 0.2s;
}
.icon-btn:hover {
  background: var(--surface-variant);
}
.profile {
  display: flex;
  align-items: center;
  gap: 8px;
  padding-left: 16px;
  border-left: 1px solid var(--outline-variant);
  cursor: pointer;
}
.avatar {
  width: 32px;
  height: 32px;
  border-radius: 999px;
  background: var(--primary-container);
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
}
.profile-text {
  font-weight: 500;
  font-size: 16px;
  color: var(--on-surface);
}
.body {
  flex: 1;
  display: flex;
  overflow: hidden;
}
.sidenav {
  flex: 0 0 auto;
  display: flex;
  flex-direction: column;
  background: var(--surface-container-lowest);
  border-right: 1px solid var(--outline-variant);
  box-shadow: var(--shadow-sm);
  padding: 16px 0 0;
  overflow: hidden;
  transition: width 0.22s ease;
}
.sidenav--collapsed {
  width: 72px;
}
.sidenav--expanded {
  width: 256px;
}
.sidenav-head {
  flex: 0 0 auto;
  padding: 0 16px 12px;
  transition: opacity 0.15s ease;
}
.sidenav--collapsed .sidenav-head {
  padding: 0 8px 12px;
}
.sidenav-cap {
  margin: 0 0 8px 0;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: var(--secondary);
  white-space: nowrap;
  overflow: hidden;
}
.sidenav-sub {
  margin: 0;
  font-size: 13px;
  line-height: 1.45;
  color: var(--outline);
}
.sidenav--collapsed .sidenav-cap,
.sidenav--collapsed .sidenav-sub {
  opacity: 0;
  height: 0;
  margin: 0;
  overflow: hidden;
  pointer-events: none;
}
.sidenav-list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
  padding: 0 8px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.nav-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  border-radius: var(--radius-lg);
  color: var(--on-surface-variant);
  text-decoration: none;
  border-left: 4px solid transparent;
  transition:
    background 0.2s,
    color 0.2s,
    border-color 0.2s,
    padding 0.22s ease,
    gap 0.22s ease;
  flex-shrink: 0;
}
.nav-icon {
  flex: 0 0 auto;
  font-size: 22px;
}
.nav-label {
  flex: 1;
  min-width: 0;
  font-size: 15px;
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  transition: opacity 0.15s ease;
}
.sidenav--collapsed .nav-item {
  justify-content: center;
  padding: 12px 10px;
  gap: 0;
  border-left-color: transparent !important;
}
.sidenav--collapsed .nav-label {
  width: 0;
  opacity: 0;
  overflow: hidden;
}
.nav-item:hover {
  color: var(--primary);
  background: var(--surface-variant);
}
.nav-item.active {
  background: var(--surface-variant);
  color: var(--primary);
  border-left-color: var(--primary);
}
.sidenav--collapsed .nav-item.active {
  background: var(--primary-container);
  color: var(--primary);
}
.sidenav-footer {
  flex: 0 0 auto;
  padding: 10px 8px 12px;
  border-top: 1px solid var(--outline-variant);
  margin-top: auto;
}
.sidenav-toggle {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 10px 12px;
  border: none;
  border-radius: var(--radius-lg);
  background: var(--surface-variant);
  color: var(--on-surface-variant);
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.2s, color 0.2s;
}
.sidenav-toggle:hover {
  background: color-mix(in srgb, var(--primary) 12%, var(--surface-variant));
  color: var(--primary);
}
.sidenav-toggle .material-symbols-outlined {
  font-size: 22px;
}
.sidenav--collapsed .sidenav-toggle {
  padding: 10px;
}
.sidenav-toggle-label {
  white-space: nowrap;
}
.sidenav--collapsed .sidenav-toggle-label {
  display: none;
}
.canvas {
  flex: 1;
  overflow: hidden;
  background: var(--background);
  display: flex;
  flex-direction: column;
  min-height: 0;
}
@media (max-width: 900px) {
  .sidenav {
    display: none;
  }
}
</style>
