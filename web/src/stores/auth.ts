import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { configureAuth, http } from '@/services/http'
import { setLocale, type Locale } from '@/i18n'

export type Permission =
  | 'VIEW_LIVE'
  | 'VIEW_PLAYBACK'
  | 'EXPORT_VIDEO'
  | 'CONTROL_PTZ'
  | 'CONFIGURE_CAMERA'
  | 'MANAGE_USERS'
  | 'MANAGE_ROLES'
  | 'MANAGE_SYSTEM'
  | 'HOLD_EVIDENCE'
  | 'TALK_AUDIO'

export interface UserSummary {
  id: string
  email: string
  displayName: string
  language: Locale
  roles: string[]
}

interface TokenResponse {
  accessToken: string
  expiresAt: string
  user: UserSummary
  landingPage: string
  recoveryCodes?: string[] | null
}

interface TwoFactorResponse {
  twoFactorRequired: true
  challengeId: string
  setupRequired: boolean
}

/** Kết quả bước mật khẩu: vào luôn, hoặc còn bước xác thực hai lớp (US-31). */
export type LoginStep =
  | { kind: 'done'; landingPage: string }
  | { kind: 'twoFactor'; challengeId: string; setupRequired: boolean }

interface Me extends UserSummary {
  permissions: Permission[]
}

/** Trạng thái đăng nhập (US-30). Access token sống trong bộ nhớ, tự làm mới trước khi hết hạn 1 phút. */
export const useAuthStore = defineStore('auth', () => {
  const accessToken = ref<string | null>(null)
  const user = ref<UserSummary | null>(null)
  const permissions = ref<Set<Permission>>(new Set())
  const landingPage = ref('/')
  const sessionExpired = ref(false)
  let timer: ReturnType<typeof setTimeout> | undefined
  let initPromise: Promise<void> | null = null

  const isAuthenticated = computed(() => accessToken.value !== null)

  function can(p: Permission) {
    return permissions.value.has(p)
  }

  async function apply(t: TokenResponse) {
    accessToken.value = t.accessToken
    user.value = t.user
    landingPage.value = t.landingPage
    sessionExpired.value = false
    setLocale(t.user.language)
    schedule(new Date(t.expiresAt).getTime() - Date.now() - 60_000)
    const me = await http.get<Me>('/me')
    permissions.value = new Set(me.permissions)
  }

  function schedule(ms: number) {
    clearTimeout(timer)
    timer = setTimeout(() => void refresh(), Math.max(ms, 5_000))
  }

  async function login(email: string, password: string): Promise<LoginStep> {
    const r = await http.post<TokenResponse | TwoFactorResponse>('/auth/login', { email, password }, { noRefresh: true })
    if ('twoFactorRequired' in r) {
      return { kind: 'twoFactor', challengeId: r.challengeId, setupRequired: r.setupRequired }
    }
    await apply(r)
    return { kind: 'done', landingPage: r.landingPage }
  }

  /** Bước thiết lập 2FA trong lúc đăng nhập: lấy secret và URI otpauth để hiện mã QR. */
  function beginTwoFactorSetup(challengeId: string) {
    return http.post<{ secret: string; otpauthUri: string }>('/auth/2fa/setup', { challengeId }, { noRefresh: true })
  }

  /** Bước 2: mã TOTP hoặc mã dự phòng. Trả mã dự phòng nếu vừa thiết lập 2FA (chỉ hiện một lần). */
  async function verifyTwoFactor(challengeId: string, code: string) {
    const t = await http.post<TokenResponse>('/auth/2fa/verify', { challengeId, code }, { noRefresh: true })
    await apply(t)
    return { landingPage: t.landingPage, recoveryCodes: t.recoveryCodes ?? [] }
  }

  async function refresh(): Promise<boolean> {
    try {
      const t = await http.post<TokenResponse>('/auth/refresh', {}, { noRefresh: true })
      await apply(t)
      return true
    } catch {
      return false
    }
  }

  function clear() {
    clearTimeout(timer)
    accessToken.value = null
    user.value = null
    permissions.value = new Set()
  }

  async function logout() {
    try {
      await http.post('/auth/logout', {}, { noRefresh: true })
    } finally {
      clear()
    }
  }

  /** Lần mở trang đầu: thử khôi phục phiên từ cookie refresh. */
  function init() {
    initPromise ??= refresh().then(() => undefined)
    return initPromise
  }

  async function updateLanguage(language: Locale) {
    setLocale(language)
    if (user.value) {
      await http.patch('/me', { language })
      user.value = { ...user.value, language }
    }
  }

  configureAuth({
    getToken: () => accessToken.value,
    refresh,
    onSessionExpired: () => {
      clear()
      sessionExpired.value = true
    },
  })

  return {
    accessToken,
    user,
    permissions,
    landingPage,
    sessionExpired,
    isAuthenticated,
    can,
    login,
    beginTwoFactorSetup,
    verifyTwoFactor,
    logout,
    refresh,
    init,
    updateLanguage,
  }
})
