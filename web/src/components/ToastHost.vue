<script setup lang="ts">
import { useToastStore } from '@/stores/toast'

const toasts = useToastStore()
const color: Record<string, string> = {
  ok: 'var(--success)',
  warn: 'var(--warning)',
  danger: 'var(--danger)',
  info: 'var(--primary)',
}
</script>

<template>
  <div
    class="toast-host"
    aria-live="polite"
  >
    <div
      v-for="t in toasts.items"
      :key="t.id"
      class="toast"
      :style="{ borderLeftColor: color[t.kind] }"
      role="status"
      @click="toasts.dismiss(t.id)"
    >
      {{ t.text }}
    </div>
  </div>
</template>
