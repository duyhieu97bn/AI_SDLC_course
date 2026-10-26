import { defineStore } from 'pinia'
import { ref, watch } from 'vue'

export type Theme = 'dark' | 'light'
const STORAGE_KEY = 'camhub-theme'

function readStored(): Theme | null {
  try {
    const v = localStorage.getItem(STORAGE_KEY)
    return v === 'dark' || v === 'light' ? v : null
  } catch {
    return null // trình duyệt chặn storage
  }
}

/** Nền tối mặc định (PRD 7.2); lựa chọn của người dùng được nhớ trên trình duyệt. */
export const useThemeStore = defineStore('theme', () => {
  const theme = ref<Theme>(readStored() ?? 'dark')

  watch(
    theme,
    (t) => {
      document.documentElement.setAttribute('data-theme', t)
      try {
        localStorage.setItem(STORAGE_KEY, t)
      } catch {
        /* bỏ qua */
      }
    },
    { immediate: true },
  )

  function toggle() {
    theme.value = theme.value === 'dark' ? 'light' : 'dark'
  }

  return { theme, toggle }
})
