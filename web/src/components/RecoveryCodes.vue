<script setup lang="ts">
import { useI18n } from 'vue-i18n'

/** Hiện 10 mã dự phòng một lần duy nhất (US-31 AC2), cho tải về dạng .txt. */
const props = defineProps<{ codes: string[] }>()
const { t } = useI18n()

function download() {
  const blob = new Blob([`CamHub – ${t('twoFactor.recoveryTitle')}\n\n${props.codes.join('\n')}\n`], { type: 'text/plain' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = 'camhub-recovery-codes.txt'
  a.click()
  URL.revokeObjectURL(a.href)
}
</script>

<template>
  <div class="stack">
    <div class="alert-box warn">
      {{ t('twoFactor.recoveryNote') }}
    </div>
    <div
      class="mono"
      style="display: grid; grid-template-columns: repeat(2, 1fr); gap: 6px; font-size: 14px"
      data-testid="recovery-codes"
    >
      <span
        v-for="c in codes"
        :key="c"
      >{{ c }}</span>
    </div>
    <button
      class="btn btn-sm"
      style="align-self: flex-start"
      @click="download"
    >
      {{ t('twoFactor.download') }}
    </button>
  </div>
</template>
