import { defineStore } from 'pinia'
import { ref } from 'vue'
import { ApiError } from '@/services/http'
import { errorText } from '@/i18n'

export interface Toast {
  id: number
  kind: 'ok' | 'warn' | 'danger' | 'info'
  text: string
}

export const useToastStore = defineStore('toast', () => {
  const items = ref<Toast[]>([])
  let seq = 0

  function push(kind: Toast['kind'], text: string, ms = 4000) {
    const id = ++seq
    items.value.push({ id, kind, text })
    setTimeout(() => dismiss(id), ms)
  }

  function dismiss(id: number) {
    items.value = items.value.filter((t) => t.id !== id)
  }

  /** Hiện lỗi API theo mã lỗi đã dịch. */
  function error(e: unknown) {
    if (e instanceof ApiError) push('danger', errorText(e.code, e.message), 6000)
    else push('danger', e instanceof Error ? e.message : String(e), 6000)
  }

  return { items, push, dismiss, error, ok: (t: string) => push('ok', t) }
})
