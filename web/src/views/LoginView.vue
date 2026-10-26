<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import AppIcon from '@/components/AppIcon.vue'
import QrCode from '@/components/QrCode.vue'
import RecoveryCodes from '@/components/RecoveryCodes.vue'
import { useAuthStore } from '@/stores/auth'
import { ApiError } from '@/services/http'
import { errorText } from '@/i18n'

/**
 * Đăng nhập (US-30, design/login.html) và xác thực hai lớp (US-31): bước mật khẩu → bước mã 6 số hoặc mã dự phòng.
 * Vai trò bắt buộc 2FA mà chưa thiết lập: quét QR, nhập mã đầu tiên, lưu 10 mã dự phòng rồi mới vào hệ thống.
 */
const { t } = useI18n()
const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

type Step = 'password' | 'otp' | 'setup' | 'codes'
const step = ref<Step>('password')
const email = ref('')
const password = ref('')
const code = ref('')
const challengeId = ref('')
const setup = ref<{ secret: string; otpauthUri: string } | null>(null)
const recoveryCodes = ref<string[]>([])
const landing = ref('/')
const error = ref('')
const busy = ref(false)

function fail(e: unknown) {
  error.value = e instanceof ApiError ? errorText(e.code, e.message) : t('errors.NETWORK')
  // Hết hạn hoặc khóa: quay lại bước mật khẩu
  if (e instanceof ApiError && ['TWO_FACTOR_EXPIRED', 'ACCOUNT_LOCKED'].includes(e.code)) {
    step.value = 'password'
    code.value = ''
  }
}

async function go(to: string) {
  const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : null
  await router.replace(redirect && redirect.startsWith('/') ? redirect : to)
}

async function submitPassword() {
  error.value = ''
  busy.value = true
  try {
    const r = await auth.login(email.value, password.value)
    if (r.kind === 'done') {
      await go(r.landingPage)
      return
    }
    challengeId.value = r.challengeId
    code.value = ''
    if (r.setupRequired) {
      setup.value = await auth.beginTwoFactorSetup(r.challengeId)
      step.value = 'setup'
    } else {
      step.value = 'otp'
    }
  } catch (e) {
    fail(e)
  } finally {
    busy.value = false
  }
}

async function submitCode() {
  error.value = ''
  busy.value = true
  try {
    const r = await auth.verifyTwoFactor(challengeId.value, code.value.trim())
    landing.value = r.landingPage
    if (r.recoveryCodes.length) {
      recoveryCodes.value = r.recoveryCodes
      step.value = 'codes'
    } else {
      await go(r.landingPage)
    }
  } catch (e) {
    fail(e)
  } finally {
    busy.value = false
  }
}

function back() {
  step.value = 'password'
  error.value = ''
  code.value = ''
}
</script>

<template>
  <main class="auth-page">
    <div class="card auth-card">
      <div class="brand">
        <span class="brand-mark"><AppIcon name="camera" /></span> CamHub
      </div>

      <div
        v-if="error"
        class="alert-box danger"
        role="alert"
        data-testid="login-error"
        style="margin-bottom: 12px"
      >
        <AppIcon name="warning" /><span>{{ error }}</span>
      </div>

      <form
        v-if="step === 'password'"
        class="stack"
        autocomplete="on"
        @submit.prevent="submitPassword"
      >
        <div>
          <h1>{{ t('login.title') }}</h1>
          <p class="muted small">
            {{ t('login.subtitle') }}
          </p>
        </div>
        <div
          v-if="auth.sessionExpired && !error"
          class="alert-box warn"
          role="alert"
        >
          <AppIcon name="info" /><span>{{ t('login.sessionExpired') }}</span>
        </div>
        <div class="field">
          <label for="email">{{ t('login.email') }}</label>
          <input
            id="email"
            v-model="email"
            class="input"
            type="text"
            autocapitalize="none"
            spellcheck="false"
            autocomplete="username"
            required
            autofocus
          >
        </div>
        <div class="field">
          <label for="password">{{ t('login.password') }}</label>
          <input
            id="password"
            v-model="password"
            class="input"
            type="password"
            autocomplete="current-password"
            required
          >
        </div>
        <button
          class="btn btn-primary btn-block"
          type="submit"
          :disabled="busy"
        >
          {{ busy ? t('common.loading') : t('login.submit') }}
        </button>
      </form>

      <form
        v-else-if="step === 'otp' || step === 'setup'"
        class="stack"
        @submit.prevent="submitCode"
      >
        <div>
          <h1>{{ t('twoFactor.title') }}</h1>
          <p class="muted small">
            {{ step === 'setup' ? t('twoFactor.setupHint') : t('twoFactor.otpHint') }}
          </p>
        </div>
        <div
          v-if="step === 'setup' && setup"
          class="stack"
          style="align-items: center"
        >
          <QrCode :value="setup.otpauthUri" />
          <span
            class="mono small"
            data-testid="totp-secret"
          >{{ setup.secret }}</span>
        </div>
        <div class="field">
          <label for="otp">{{ step === 'setup' ? t('twoFactor.firstCode') : t('twoFactor.code') }}</label>
          <input
            id="otp"
            v-model="code"
            class="input mono"
            inputmode="text"
            autocomplete="one-time-code"
            maxlength="9"
            style="font-size: 20px; letter-spacing: 4px; text-align: center; height: 48px"
            required
            autofocus
          >
          <span
            v-if="step === 'otp'"
            class="hint"
          >{{ t('twoFactor.recoveryHint') }}</span>
        </div>
        <button
          class="btn btn-primary btn-block"
          type="submit"
          :disabled="busy || code.trim().length < 6"
          data-testid="otp-submit"
        >
          {{ t('twoFactor.confirm') }}
        </button>
        <button
          class="btn btn-ghost btn-block"
          type="button"
          @click="back"
        >
          {{ t('twoFactor.back') }}
        </button>
      </form>

      <div
        v-else
        class="stack"
      >
        <h1>{{ t('twoFactor.recoveryTitle') }}</h1>
        <RecoveryCodes :codes="recoveryCodes" />
        <button
          class="btn btn-primary btn-block"
          data-testid="codes-continue"
          @click="go(landing)"
        >
          {{ t('twoFactor.savedContinue') }}
        </button>
      </div>

      <p
        class="small muted"
        style="margin-top: 20px"
      >
        {{ t('login.note') }}
      </p>
    </div>
  </main>
</template>
