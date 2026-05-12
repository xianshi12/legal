<script setup>
import { computed, onUnmounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../../stores/auth'
import { requestCaptcha } from '../../api/auth'
import justiceScale from '../../assets/login-justice-scale.png'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const mode = ref('login')
const role = ref('user')
const loginMethod = ref('password')
const captchaCountdown = ref(0)
let captchaTimer = null

const loginForm = reactive({
  phone: '',
  password: '',
  code: '',
  remember: true,
})

const registerForm = reactive({
  phone: '',
  code: '',
  password: '',
  confirmPassword: '',
  licenseNo: '',
  agree: true,
})

const isRegister = computed(() => mode.value === 'register')
const roleLabel = computed(() => (role.value === 'lawyer' ? '律师' : '用户'))
const visualTitle = computed(() => {
  if (isRegister.value) return '加入法通AI'
  return '欢迎回来'
})
const visualSubtitle = computed(() =>
  isRegister.value
    ? `以${roleLabel.value}身份创建账号，进入专业、清晰、高效的法律服务工作台。`
    : `请以${roleLabel.value}身份登录，继续使用法通AI助手。`,
)
const formTitle = computed(() => {
  if (isRegister.value) return '创建账号'
  return '账号登录'
})
const formSubtitle = computed(() =>
  isRegister.value
    ? '实名资料可稍后在个人中心完善。'
    : '请选择身份并输入登录凭证。',
)
const captchaButtonText = computed(() => (captchaCountdown.value > 0 ? `${captchaCountdown.value}s后重试` : '获取验证码'))

function switchMode(nextMode) {
  if (mode.value === nextMode) return
  mode.value = nextMode
}

function isValidPhone(phone) {
  return /^1[3-9]\d{9}$/.test(String(phone || '').trim())
}

function isValidPassword(password) {
  return /^\S{8,11}$/.test(String(password || ''))
}

function isValidCode(code) {
  return /^\d{6}$/.test(String(code || '').trim())
}

function currentCaptchaPhone() {
  if (isRegister.value) return registerForm.phone
  return loginForm.phone
}

function startCaptchaCountdown() {
  captchaCountdown.value = 60
  if (captchaTimer) clearInterval(captchaTimer)
  captchaTimer = window.setInterval(() => {
    captchaCountdown.value -= 1
    if (captchaCountdown.value <= 0) {
      clearInterval(captchaTimer)
      captchaTimer = null
      captchaCountdown.value = 0
    }
  }, 1000)
}

async function sendCode() {
  const phone = currentCaptchaPhone()
  if (!isValidPhone(phone)) {
    ElMessage.warning('请输入有效的中国大陆11位手机号')
    return
  }
  if (captchaCountdown.value > 0) return
  try {
    await requestCaptcha({
      phone,
      scene: isRegister.value ? 'register' : 'login',
    })
    startCaptchaCountdown()
    ElMessage.success('验证码已发送，请在后端控制台查看，5分钟内有效')
  } catch (error) {
    ElMessage.error(error.message || '验证码发送失败')
  }
}

function goAfterLogin() {
  const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/dashboard'
  router.push(redirect || '/dashboard')
}

async function submitLogin() {
  try {
    if (loginMethod.value === 'password') {
      if (!isValidPhone(loginForm.phone)) {
        ElMessage.warning('请输入有效的中国大陆11位手机号')
        return
      }
      if (!isValidPassword(loginForm.password)) {
        ElMessage.warning('密码需为8-11位，且不能包含空格')
        return
      }
      await auth.loginWithPassword({
        phone: loginForm.phone,
        password: loginForm.password,
        role: role.value,
        remember: loginForm.remember,
      })
    } else {
      if (!isValidPhone(loginForm.phone)) {
        ElMessage.warning('请输入有效的中国大陆11位手机号')
        return
      }
      if (!isValidCode(loginForm.code)) {
        ElMessage.warning('验证码需为6位数字')
        return
      }
      await auth.loginWithSms({
        phone: loginForm.phone,
        code: loginForm.code,
        role: role.value,
        remember: loginForm.remember,
      })
    }
    ElMessage.success(`${roleLabel.value}登录成功`)
    goAfterLogin()
  } catch (error) {
    ElMessage.error(error.message || '登录失败')
  }
}

async function submitRegister() {
  if (!isValidPhone(registerForm.phone)) {
    ElMessage.warning('请输入有效的中国大陆11位手机号')
    return
  }
  if (role.value === 'lawyer' && !registerForm.licenseNo) {
    ElMessage.warning('律师注册请填写执业证号')
    return
  }
  if (!isValidCode(registerForm.code)) {
    ElMessage.warning('验证码需为6位数字')
    return
  }
  if (!isValidPassword(registerForm.password)) {
    ElMessage.warning('密码需为8-11位，且不能包含空格')
    return
  }
  if (registerForm.password !== registerForm.confirmPassword) {
    ElMessage.warning('两次输入的密码不一致')
    return
  }
  if (!registerForm.agree) {
    ElMessage.warning('请先阅读并同意服务协议')
    return
  }
  try {
    await auth.register({
      role: role.value,
      phone: registerForm.phone,
      code: registerForm.code,
      password: registerForm.password,
      licenseNo: registerForm.licenseNo,
      remember: true,
    })
    loginForm.phone = registerForm.phone
    ElMessage.success(`${roleLabel.value}账号创建成功`)
    goAfterLogin()
  } catch (error) {
    ElMessage.error(error.message || '注册失败')
  }
}

onUnmounted(() => {
  if (captchaTimer) clearInterval(captchaTimer)
})
</script>

<template>
  <div class="auth-page">
    <header class="auth-header">
      <button class="brand" type="button" @click="router.push('/login')">
        <span class="material-symbols-outlined fill">gavel</span>
        <span>法通AI助手</span>
      </button>
      <div class="header-tools" aria-label="页面工具">
        <button class="icon-button" type="button" title="帮助中心">
          <span class="material-symbols-outlined">help</span>
        </button>
        <button class="icon-button" type="button" title="语言设置">
          <span class="material-symbols-outlined">language</span>
        </button>
      </div>
    </header>

    <main class="auth-main">
      <section class="auth-card" :class="{ 'is-register': isRegister }" aria-label="登录注册区域">
        <div class="visual-panel">
          <img :src="justiceScale" alt="法律天秤" />
          <div class="visual-overlay"></div>
          <div class="visual-copy">
            <span class="material-symbols-outlined">balance</span>
            <p class="visual-kicker">{{ visualTitle }}</p>
            <h1>{{ isRegister ? '以专业身份同行' : '法通AI，守护正义' }}</h1>
            <p>{{ visualSubtitle }}</p>
          </div>
        </div>

        <div class="form-panel">
          <div class="form-scroll">
            <div class="form-heading">
              <p class="eyebrow">{{ isRegister ? 'Register' : 'Legal AI Workspace' }}</p>
              <h2>{{ formTitle }}</h2>
              <p>{{ formSubtitle }}</p>
            </div>

            <div class="role-switch" role="tablist" aria-label="身份选择">
              <button
                type="button"
                :class="{ active: role === 'user' }"
                role="tab"
                :aria-selected="role === 'user'"
                @click="role = 'user'"
              >
                我是用户
              </button>
              <button
                type="button"
                :class="{ active: role === 'lawyer' }"
                role="tab"
                :aria-selected="role === 'lawyer'"
                @click="role = 'lawyer'"
              >
                我是律师
              </button>
            </div>

            <Transition name="fade-slide" mode="out-in">
              <form v-if="!isRegister" key="login" class="auth-form" @submit.prevent="submitLogin">
                <div class="method-tabs" role="tablist" aria-label="登录方式">
                  <button
                    type="button"
                    :class="{ active: loginMethod === 'password' }"
                    @click="loginMethod = 'password'"
                  >
                    密码登录
                  </button>
                  <button
                    type="button"
                    :class="{ active: loginMethod === 'sms' }"
                    @click="loginMethod = 'sms'"
                  >
                    验证码登录
                  </button>
                </div>

                <template v-if="loginMethod === 'password'">
                  <label class="field">
                    <span>手机号</span>
                    <el-input v-model="loginForm.phone" maxlength="11" size="large" placeholder="请输入11位手机号">
                      <template #prefix>
                        <span class="material-symbols-outlined input-icon">person</span>
                      </template>
                    </el-input>
                  </label>
                  <label class="field">
                    <span>密码</span>
                    <el-input
                      v-model="loginForm.password"
                      size="large"
                      type="password"
                      show-password
                      placeholder="请输入密码"
                    >
                      <template #prefix>
                        <span class="material-symbols-outlined input-icon">lock</span>
                      </template>
                    </el-input>
                  </label>
                </template>

                <template v-else>
                  <label class="field">
                    <span>手机号</span>
                    <el-input v-model="loginForm.phone" maxlength="11" size="large" placeholder="请输入11位手机号">
                      <template #prefix>
                        <span class="material-symbols-outlined input-icon">smartphone</span>
                      </template>
                    </el-input>
                  </label>
                  <label class="field">
                    <span>验证码</span>
                    <div class="code-row">
                      <el-input v-model="loginForm.code" maxlength="6" size="large" placeholder="请输入6位验证码">
                        <template #prefix>
                          <span class="material-symbols-outlined input-icon">verified_user</span>
                        </template>
                      </el-input>
                      <button class="light-button" type="button" :disabled="captchaCountdown > 0" @click="sendCode">
                        {{ captchaButtonText }}
                      </button>
                    </div>
                  </label>
                </template>

                <div class="form-options">
                  <el-checkbox v-model="loginForm.remember">记住我</el-checkbox>
                  <button class="text-button" type="button" @click="router.push('/forgot-password')">忘记密码？</button>
                </div>

                <button class="primary-button" type="submit">
                  登录
                  <span class="material-symbols-outlined">arrow_forward</span>
                </button>

                <p class="mode-link">
                  还没有账号？
                  <button type="button" @click="switchMode('register')">立即注册开启法律服务之旅</button>
                </p>
              </form>

              <form v-else key="register" class="auth-form" @submit.prevent="submitRegister">
                <label class="field">
                  <span>手机号</span>
                  <el-input v-model="registerForm.phone" maxlength="11" size="large" placeholder="请输入11位手机号">
                    <template #prefix>
                      <span class="material-symbols-outlined input-icon">smartphone</span>
                    </template>
                  </el-input>
                </label>

                <label class="field">
                  <span>验证码</span>
                  <div class="code-row">
                    <el-input v-model="registerForm.code" maxlength="6" size="large" placeholder="请输入6位验证码">
                      <template #prefix>
                        <span class="material-symbols-outlined input-icon">verified_user</span>
                      </template>
                    </el-input>
                    <button class="light-button" type="button" :disabled="captchaCountdown > 0" @click="sendCode">
                      {{ captchaButtonText }}
                    </button>
                  </div>
                </label>

                <label v-if="role === 'lawyer'" class="field">
                  <span>执业证号</span>
                  <el-input v-model="registerForm.licenseNo" size="large" placeholder="请输入律师执业证号">
                    <template #prefix>
                      <span class="material-symbols-outlined input-icon">workspace_premium</span>
                    </template>
                  </el-input>
                </label>

                <label class="field">
                  <span>设置密码</span>
                  <el-input
                    v-model="registerForm.password"
                    size="large"
                    type="password"
                    show-password
                    placeholder="请设置8-11位密码"
                  >
                    <template #prefix>
                      <span class="material-symbols-outlined input-icon">lock</span>
                    </template>
                  </el-input>
                </label>

                <label class="field">
                  <span>确认密码</span>
                  <el-input
                    v-model="registerForm.confirmPassword"
                    size="large"
                    type="password"
                    show-password
                    placeholder="请再次输入8-11位密码"
                  >
                    <template #prefix>
                      <span class="material-symbols-outlined input-icon">lock_reset</span>
                    </template>
                  </el-input>
                </label>

                <el-checkbox v-model="registerForm.agree">
                  我已阅读并同意《服务协议》和《隐私政策》
                </el-checkbox>

                <button class="primary-button" type="submit">
                  注册
                  <span class="material-symbols-outlined">how_to_reg</span>
                </button>

                <p class="mode-link">
                  已有账号？
                  <button type="button" @click="switchMode('login')">返回登录</button>
                </p>
              </form>
            </Transition>
          </div>
        </div>
      </section>
    </main>

    <footer class="auth-footer">
      <span>法通AI助手</span>
      <span>© 2026 法通AI 智能法律服务平台</span>
      <nav aria-label="底部链接">
        <a href="#">服务条款</a>
        <a href="#">隐私政策</a>
        <a href="#">联系我们</a>
      </nav>
    </footer>
  </div>
</template>

<style scoped>
.auth-page {
  min-height: 100%;
  display: flex;
  flex-direction: column;
  background:
    radial-gradient(circle at top left, rgba(0, 93, 172, 0.1), transparent 34%),
    linear-gradient(180deg, #f8fbff 0%, #edf4fb 100%);
  color: var(--on-surface);
  overflow-y: auto;
}

.auth-header {
  height: 64px;
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 40px;
  background: rgba(255, 255, 255, 0.82);
  border-bottom: 1px solid rgba(193, 198, 212, 0.7);
  backdrop-filter: blur(12px);
}

.brand {
  border: 0;
  background: transparent;
  display: inline-flex;
  align-items: center;
  gap: 9px;
  color: var(--primary);
  font-size: 21px;
  line-height: 1;
  font-weight: 800;
  cursor: pointer;
}

.brand .material-symbols-outlined {
  font-size: 27px;
}

.header-tools {
  display: flex;
  align-items: center;
  gap: 8px;
}

.icon-button {
  width: 38px;
  height: 38px;
  border: 0;
  border-radius: 999px;
  background: transparent;
  color: var(--on-surface-variant);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: background 0.2s ease, color 0.2s ease;
}

.icon-button:hover {
  background: #eaf2fb;
  color: var(--primary);
}

.auth-main {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 38px 24px;
}

.auth-card {
  width: min(960px, 100%);
  height: clamp(590px, calc(100vh - 168px), 660px);
  min-height: 590px;
  position: relative;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  overflow: hidden;
  border: 1px solid rgba(193, 198, 212, 0.76);
  border-radius: 12px;
  background: #ffffff;
  box-shadow: 0 22px 60px rgba(16, 58, 105, 0.14);
}

.visual-panel,
.form-panel {
  min-width: 0;
  height: 100%;
  min-height: 0;
  transition:
    transform 0.58s cubic-bezier(0.22, 1, 0.36, 1),
    border-radius 0.58s cubic-bezier(0.22, 1, 0.36, 1);
}

.visual-panel {
  position: relative;
  z-index: 2;
  grid-column: 1;
  overflow: hidden;
  background: #0a376f;
  transform: translateX(0);
}

.visual-panel img {
  width: 100%;
  height: 100%;
  display: block;
  object-fit: cover;
  filter: saturate(0.98) contrast(1.04);
}

.visual-overlay {
  position: absolute;
  inset: 0;
  background:
    linear-gradient(180deg, rgba(5, 37, 84, 0.18) 0%, rgba(0, 72, 160, 0.82) 100%),
    linear-gradient(90deg, rgba(0, 42, 105, 0.8), rgba(0, 93, 172, 0.32));
}

.visual-copy {
  position: absolute;
  inset: 0;
  z-index: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 46px;
  color: #ffffff;
  text-align: center;
}

.visual-copy .material-symbols-outlined {
  font-size: 58px;
  margin-bottom: 18px;
}

.visual-kicker {
  margin: 0 0 14px;
  padding: 6px 14px;
  border: 1px solid rgba(255, 255, 255, 0.34);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.14);
  color: rgba(255, 255, 255, 0.92);
  font-size: 13px;
  line-height: 1.4;
  font-weight: 800;
  backdrop-filter: blur(10px);
}

.visual-copy h1 {
  margin: 0 0 14px;
  font-size: 28px;
  line-height: 1.25;
  font-weight: 800;
}

.visual-copy p {
  max-width: 310px;
  margin: 0;
  color: rgba(255, 255, 255, 0.88);
  font-size: 15px;
  line-height: 1.8;
}

.form-panel {
  z-index: 1;
  grid-column: 2;
  background:
    linear-gradient(180deg, rgba(248, 251, 255, 0.98), rgba(255, 255, 255, 1) 28%),
    #ffffff;
  transform: translateX(0);
  overflow: hidden;
}

.auth-card.is-register .visual-panel {
  transform: translateX(100%);
}

.auth-card.is-register .form-panel {
  transform: translateX(-100%);
}

.form-scroll {
  height: 100%;
  overflow-y: hidden;
  padding: 38px 46px 34px;
  box-sizing: border-box;
  scrollbar-width: thin;
  scrollbar-color: #9ab5d5 transparent;
}

.form-scroll::-webkit-scrollbar {
  width: 7px;
}

.form-scroll::-webkit-scrollbar-track {
  background: transparent;
}

.form-scroll::-webkit-scrollbar-thumb {
  background: #9ab5d5;
  border-radius: 999px;
}

.form-scroll::-webkit-scrollbar-thumb:hover {
  background: #6f93bd;
}

.form-heading {
  margin-bottom: 18px;
  padding-bottom: 14px;
  border-bottom: 1px solid rgba(216, 223, 235, 0.72);
}

.eyebrow {
  margin: 0 0 6px;
  color: var(--primary);
  font-size: 13px;
  line-height: 1.4;
  font-weight: 800;
}

.form-heading h2 {
  margin: 0 0 6px;
  font-size: 26px;
  line-height: 1.25;
  font-weight: 800;
  color: #182236;
}

.form-heading p {
  margin: 0;
  color: var(--on-surface-variant);
  font-size: 14px;
  line-height: 1.7;
}

.role-switch {
  position: relative;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 4px;
  padding: 4px;
  margin-bottom: 18px;
  border-radius: 999px;
  border: 1px solid rgba(213, 222, 234, 0.88);
  background: #edf3f9;
}

.role-switch button {
  height: 34px;
  border: 0;
  border-radius: 999px;
  background: transparent;
  color: #536176;
  font-size: 14px;
  font-weight: 800;
  cursor: pointer;
  transition: background 0.2s ease, color 0.2s ease, box-shadow 0.2s ease;
}

.role-switch button.active {
  background: #ffffff;
  color: var(--primary);
  box-shadow: 0 4px 14px rgba(16, 58, 105, 0.12);
}

.auth-form {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.auth-card.is-register .form-scroll {
  padding-top: 30px;
  padding-bottom: 28px;
}

.auth-card.is-register .form-heading {
  margin-bottom: 14px;
}

.auth-card.is-register .role-switch {
  margin-bottom: 14px;
}

.auth-card.is-register .auth-form {
  gap: 11px;
}

.auth-card.is-register :deep(.el-input__wrapper) {
  min-height: 40px;
}

.auth-card.is-register .field {
  gap: 5px;
}

.auth-card.is-register .primary-button {
  height: 44px;
}

.auth-card.is-register .mode-link {
  line-height: 1.45;
}

.method-tabs {
  display: grid;
  grid-template-columns: 1fr 1fr;
  border-bottom: 1px solid #d8dfeb;
  margin-bottom: 2px;
}

.method-tabs button {
  border: 0;
  border-bottom: 2px solid transparent;
  background: transparent;
  color: #58657a;
  padding: 11px 8px;
  font-size: 14px;
  font-weight: 800;
  cursor: pointer;
  transition: color 0.2s ease, border-color 0.2s ease;
}

.method-tabs button.active {
  color: var(--primary);
  border-bottom-color: var(--primary);
}

.field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  color: #4b5870;
  font-size: 13px;
  font-weight: 700;
}

.field > span {
  padding-left: 2px;
}

.input-icon {
  color: #8c98aa;
  font-size: 20px;
}

.code-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 112px;
  gap: 10px;
}

.light-button,
.primary-button,
.mode-link button,
.text-button {
  font-family: inherit;
}

.light-button {
  border: 1px solid #c8d7ec;
  border-radius: 8px;
  background: #eef6ff;
  color: var(--primary);
  font-weight: 800;
  font-size: 13px;
  cursor: pointer;
  transition: background 0.2s ease, border-color 0.2s ease;
}

.light-button:hover {
  background: #e1efff;
  border-color: var(--primary);
}

.light-button:disabled {
  cursor: not-allowed;
  color: #7d8ba0;
  background: #eef1f5;
  border-color: #d4dbe6;
}

.form-options {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-height: 30px;
}

.text-button,
.auth-footer a {
  color: var(--primary);
  font-size: 13px;
  font-weight: 800;
  text-decoration: none;
}

.text-button:hover,
.auth-footer a:hover {
  text-decoration: underline;
}

.text-button {
  padding: 0;
  border: 0;
  background: transparent;
  cursor: pointer;
}

.primary-button {
  height: 46px;
  border: 0;
  border-radius: 8px;
  background: linear-gradient(135deg, #005dac 0%, #0b74de 100%);
  color: #ffffff;
  font-size: 15px;
  font-weight: 800;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  cursor: pointer;
  box-shadow: 0 10px 22px rgba(0, 93, 172, 0.23);
  transition: transform 0.2s ease, box-shadow 0.2s ease;
}

.primary-button:hover {
  transform: translateY(-1px);
  box-shadow: 0 14px 28px rgba(0, 93, 172, 0.28);
}

.mode-link {
  margin: 0;
  color: #6a778c;
  text-align: center;
  font-size: 13px;
  line-height: 1.7;
}

.mode-link button {
  border: 0;
  background: transparent;
  color: var(--primary);
  font-size: 13px;
  font-weight: 800;
  cursor: pointer;
  padding: 0 0 0 4px;
}

.mode-link button:hover {
  text-decoration: underline;
}

.auth-footer {
  min-height: 64px;
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 16px 40px;
  color: #647184;
  font-size: 12px;
  border-top: 1px solid rgba(193, 198, 212, 0.8);
  background: rgba(255, 255, 255, 0.65);
}

.auth-footer nav {
  display: flex;
  align-items: center;
  gap: 18px;
}

.fade-slide-enter-active,
.fade-slide-leave-active {
  transition: opacity 0.22s ease, transform 0.22s ease;
}

.fade-slide-enter-from {
  opacity: 0;
  transform: translateY(10px);
}

.fade-slide-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}

:deep(.el-input__wrapper) {
  min-height: 44px;
  border-radius: 8px;
  box-shadow: 0 0 0 1px #d5deea inset;
  background: #fbfdff;
}

:deep(.el-input__wrapper.is-focus) {
  box-shadow:
    0 0 0 1px var(--primary) inset,
    0 0 0 3px rgba(0, 93, 172, 0.12);
}

:deep(.el-checkbox__input.is-checked .el-checkbox__inner) {
  background-color: var(--primary);
  border-color: var(--primary);
}

:deep(.el-checkbox__input.is-checked + .el-checkbox__label) {
  color: #526176;
}

@media (max-width: 820px) {
  .auth-page {
    overflow-y: auto;
  }

  .auth-header,
  .auth-footer {
    padding-left: 18px;
    padding-right: 18px;
  }

  .brand {
    font-size: 18px;
  }

  .auth-main {
    padding: 22px 14px;
    align-items: flex-start;
  }

  .auth-card {
    height: auto;
    min-height: auto;
    display: flex;
    flex-direction: column;
  }

  .visual-panel,
  .form-panel,
  .auth-card.is-register .visual-panel,
  .auth-card.is-register .form-panel {
    height: auto;
    min-height: auto;
    transform: none;
  }

  .visual-panel {
    height: 250px;
    order: 1;
  }

  .form-panel {
    order: 2;
  }

  .auth-card.is-register .form-panel {
    order: 1;
  }

  .auth-card.is-register .visual-panel {
    order: 2;
  }

  .form-scroll {
    overflow-y: auto;
    max-height: min(620px, calc(100vh - 120px));
    padding: 28px 20px;
  }

  .visual-copy {
    padding: 26px;
  }

  .visual-copy h1,
  .form-heading h2 {
    font-size: 24px;
  }

  .auth-footer {
    flex-direction: column;
    align-items: flex-start;
  }

  .auth-footer nav {
    flex-wrap: wrap;
  }
}

@media (max-width: 420px) {
  .header-tools {
    display: none;
  }

  .code-row {
    grid-template-columns: 1fr;
  }

  .light-button {
    height: 42px;
  }
}
</style>
