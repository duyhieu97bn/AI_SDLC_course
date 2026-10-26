import { createI18n } from 'vue-i18n'
import vi from './vi.json'
import en from './en.json'

export type Locale = 'vi' | 'en'
const STORAGE_KEY = 'camhub-locale'

function initial(): Locale {
  try {
    const v = localStorage.getItem(STORAGE_KEY)
    if (v === 'vi' || v === 'en') return v
  } catch {
    /* bỏ qua */
  }
  return 'vi'
}

/** Tiếng Việt và tiếng Anh (US-41). Ngôn ngữ của người dùng lưu trong hồ sơ, đồng bộ khi đăng nhập. */
export const i18n = createI18n({
  legacy: false,
  locale: initial(),
  fallbackLocale: 'vi',
  messages: { vi, en },
  datetimeFormats: {
    vi: { short: { dateStyle: 'short', timeStyle: 'short' }, time: { timeStyle: 'medium' } },
    en: { short: { dateStyle: 'short', timeStyle: 'short' }, time: { timeStyle: 'medium' } },
  },
})

export function setLocale(l: Locale) {
  i18n.global.locale.value = l
  document.documentElement.setAttribute('lang', l)
  try {
    localStorage.setItem(STORAGE_KEY, l)
  } catch {
    /* bỏ qua */
  }
}

/** Dịch mã lỗi API sang ngôn ngữ người dùng; không có bản dịch thì dùng mô tả máy chủ trả về. */
export function errorText(code: string, fallback: string): string {
  const key = `errors.${code}`
  return i18n.global.te(key) ? i18n.global.t(key) : fallback
}
