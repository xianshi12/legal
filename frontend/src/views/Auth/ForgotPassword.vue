<script setup>
import { computed, onUnmounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import { requestCaptcha, resetPassword } from '../../api/auth'
import justiceScale from '../../assets/login-justice-scale.png'

const router = useRouter()
const role = ref('user')
const captchaCountdown = ref(0)
let captchaTimer = null

const form = reactive({
  phone: '',
  code: '',
  password: '',
  confirmPassword: '',
})

const roleLabel = computed(() => (role.value === 'lawyer' ? '律师' : '用户'))
const captchaButtonText = computed(() => (captchaCountdown.value > 0 ? `${captchaCountdown.value}s后重试` : '获取验证码'))

function isValidPhone(phone) {
  return /^1[3-9]\d{9}$/.test(String(phone || '').trim())
}

function isValidPassword(password) {
  return /^\S{8,11}$/.test(String(password || ''))
}

function isValidCode(code) {
  return /^\d{6}$/.test(String(code || '').trim())
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
  if (!isValidPhone(form.phone)) {
    ElMessage.warning('请输入有效的中国大陆11位手机号')
    return
  }
  if (captchaCountdown.value > 0) return
  try {
    await requestCaptcha({
      phone: form.phone,
      scene: 'reset-password',
    })
    startCaptchaCountdown()
    ElMessage.success('验证码已发送，请在后端控制台查看，5分钟内有效')
  } catch (error) {
    ElMessage.error(error.message || '验证码发送失败')
  }
}

async function submitReset() {
  if (!isValidPhone(form.phone)) {
    ElMessage.warning('请输入有效的中国大陆11位手机号')
    return
  }
  if (!isValidCode(form.code)) {
    ElMessage.warning('验证码需为6位数字')
    return
  }
  if (!isValidPassword(form.password)) {
    ElMessage.warning('密码需为8-11位，且不能包含空格')
    return
  }
  if (form.password !== form.confirmPassword) {
    ElMessage.warning('两次输入的新密码不一致')
    return
  }
  try {
    await resetPassword({
      role: role.value,
      phone: form.phone,
      code: form.code,
      newPassword: form.password,
    })
    ElMessage.success('密码已重置，请返回登录')
    router.push('/login')
  } catch (error) {
    ElMessage.error(error.message || '密码重置失败')
  }
}

onUnmounted(() => {
  if (captchaTimer) clearInterval(captchaTimer)
})
</script>

<template>
  <div class="forgot-page">
    <header class="forgot-header">
      <button class="brand" type="button" @click="router.push('/login')">
        <span class="material-symbols-outlined fill">gavel</span>
        <span>法通AI助手</span>
      </button>
      <button class="back-link" type="button" @click="router.push('/login')">
        <span class="material-symbols-outlined">arrow_back</span>
        返回登录
      </button>
    </header>

    <main class="forgot-main">
      <section class="forgot-card">
        <div class="visual-panel">
          <img :src="justiceScale" alt="法律天秤" />
          <div class="visual-overlay"></div>
          <div class="visual-copy">
            <span class="material-symbols-outlined">verified_user</span>
            <p class="visual-kicker">找回密码</p>
            <h1>验证身份，重设访问凭证</h1>
            <p>请输入原来绑定的手机号。验证码将由后端异步生成，并在控制台输出。</p>
          </div>
        </div>

        <div class="form-panel">
          <div class="form-heading">
            <p class="eyebrow">Password Recovery</p>
            <h2>重置密码</h2>
            <p>当前身份：{{ roleLabel }}。验证码 5 分钟内有效，1 分钟内不能重复获取。</p>
          </div>

          <div class="role-switch" role="tablist" aria-label="身份选择">
            <button type="button" :class="{ active: role === 'user' }" @click="role = 'user'">我是用户</button>
            <button type="button" :class="{ active: role === 'lawyer' }" @click="role = 'lawyer'">我是律师</button>
          </div>

          <form class="reset-form" @submit.prevent="submitReset">
            <label class="field">
              <span>原绑定手机号</span>
              <el-input v-model="form.phone" maxlength="11" size="large" placeholder="请输入11位手机号">
                <template #prefix>
                  <span class="material-symbols-outlined input-icon">smartphone</span>
                </template>
              </el-input>
            </label>

            <label class="field">
              <span>验证码</span>
              <div class="code-row">
                <el-input v-model="form.code" maxlength="6" size="large" placeholder="请输入6位验证码">
                  <template #prefix>
                    <span class="material-symbols-outlined input-icon">verified_user</span>
                  </template>
                </el-input>
                <button class="light-button" type="button" :disabled="captchaCountdown > 0" @click="sendCode">
                  {{ captchaButtonText }}
                </button>
              </div>
            </label>

            <label class="field">
              <span>新密码</span>
              <el-input v-model="form.password" size="large" type="password" show-password placeholder="请设置8-11位新密码">
                <template #prefix>
                  <span class="material-symbols-outlined input-icon">lock</span>
                </template>
              </el-input>
            </label>

            <label class="field">
              <span>确认新密码</span>
              <el-input
                v-model="form.confirmPassword"
                size="large"
                type="password"
                show-password
                placeholder="请再次输入新密码"
              >
                <template #prefix>
                  <span class="material-symbols-outlined input-icon">lock_reset</span>
                </template>
              </el-input>
            </label>

            <button class="primary-button" type="submit">
              确认重置
              <span class="material-symbols-outlined">check_circle</span>
            </button>
          </form>
        </div>
      </section>
    </main>
  </div>
</template>

<style scoped>
.forgot-page {
  min-height: 100%;
  display: flex;
  flex-direction: column;
  background:
    radial-gradient(circle at top left, rgba(0, 93, 172, 0.1), transparent 34%),
    linear-gradient(180deg, #f8fbff 0%, #edf4fb 100%);
  overflow-y: auto;
}

.forgot-header {
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

.brand,
.back-link {
  border: 0;
  background: transparent;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--primary);
  font-family: inherit;
  font-weight: 800;
  cursor: pointer;
}

.brand {
  font-size: 21px;
}

.brand .material-symbols-outlined {
  font-size: 27px;
}

.back-link {
  height: 38px;
  padding: 0 12px;
  border-radius: 999px;
  font-size: 14px;
}

.back-link:hover {
  background: #eaf2fb;
}

.forgot-main {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 38px 24px;
}

.forgot-card {
  width: min(900px, 100%);
  min-height: 560px;
  display: grid;
  grid-template-columns: 0.94fr 1.06fr;
  overflow: hidden;
  border: 1px solid rgba(193, 198, 212, 0.76);
  border-radius: 12px;
  background: #ffffff;
  box-shadow: 0 22px 60px rgba(16, 58, 105, 0.14);
}

.visual-panel {
  position: relative;
  min-height: 560px;
  overflow: hidden;
  background: #0a376f;
}

.visual-panel img {
  width: 100%;
  height: 100%;
  display: block;
  object-fit: cover;
}

.visual-overlay {
  position: absolute;
  inset: 0;
  background:
    linear-gradient(180deg, rgba(5, 37, 84, 0.16) 0%, rgba(0, 72, 160, 0.84) 100%),
    linear-gradient(90deg, rgba(0, 42, 105, 0.78), rgba(0, 93, 172, 0.32));
}

.visual-copy {
  position: absolute;
  inset: 0;
  z-index: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 44px;
  color: #ffffff;
  text-align: center;
}

.visual-copy .material-symbols-outlined {
  font-size: 54px;
  margin-bottom: 18px;
}

.visual-kicker {
  margin: 0 0 14px;
  padding: 6px 14px;
  border: 1px solid rgba(255, 255, 255, 0.34);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.14);
  font-size: 13px;
  font-weight: 800;
}

.visual-copy h1 {
  margin: 0 0 14px;
  font-size: 27px;
  line-height: 1.25;
  font-weight: 800;
}

.visual-copy p:last-child {
  max-width: 310px;
  margin: 0;
  color: rgba(255, 255, 255, 0.88);
  font-size: 15px;
  line-height: 1.8;
}

.form-panel {
  padding: 44px 48px;
  background:
    linear-gradient(180deg, rgba(248, 251, 255, 0.98), rgba(255, 255, 255, 1) 28%),
    #ffffff;
}

.form-heading {
  padding-bottom: 16px;
  margin-bottom: 18px;
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
  font-size: 27px;
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
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 4px;
  padding: 4px;
  margin-bottom: 18px;
  border: 1px solid rgba(213, 222, 234, 0.88);
  border-radius: 999px;
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
}

.role-switch button.active {
  background: #ffffff;
  color: var(--primary);
  box-shadow: 0 4px 14px rgba(16, 58, 105, 0.12);
}

.reset-form {
  display: flex;
  flex-direction: column;
  gap: 14px;
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
.primary-button {
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

@media (max-width: 820px) {
  .forgot-header {
    padding: 0 18px;
  }

  .forgot-main {
    padding: 22px 14px;
    align-items: flex-start;
  }

  .forgot-card {
    display: flex;
    flex-direction: column;
  }

  .visual-panel {
    min-height: 240px;
  }

  .form-panel {
    padding: 28px 20px;
  }
}

@media (max-width: 420px) {
  .back-link {
    padding: 0;
  }

  .code-row {
    grid-template-columns: 1fr;
  }

  .light-button {
    height: 42px;
  }
}
</style>
