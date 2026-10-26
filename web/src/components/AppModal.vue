<script setup lang="ts">
import { onBeforeUnmount, onMounted } from 'vue'
import AppIcon from './AppIcon.vue'

/** Hộp thoại theo design/ (.modal-backdrop .modal). Đóng bằng Esc, nút X hoặc bấm ra ngoài. */
withDefaults(defineProps<{ title: string; size?: 'sm' | 'md' }>(), { size: 'md' })
const emit = defineEmits<{ close: [] }>()

function onKey(e: KeyboardEvent) {
  if (e.key === 'Escape') emit('close')
}
onMounted(() => window.addEventListener('keydown', onKey))
onBeforeUnmount(() => window.removeEventListener('keydown', onKey))
</script>

<template>
  <Teleport to="body">
    <div
      class="modal-backdrop open"
      @mousedown.self="emit('close')"
    >
      <div
        :class="['modal', { sm: size === 'sm' }]"
        role="dialog"
        aria-modal="true"
        :aria-label="title"
      >
        <div class="modal-head">
          <h2>{{ title }}</h2>
          <span class="spacer" />
          <button
            class="icon-btn"
            :aria-label="$t('common.close')"
            @click="emit('close')"
          >
            <AppIcon name="x" />
          </button>
        </div>
        <div class="modal-body">
          <slot />
        </div>
        <div
          v-if="$slots.footer"
          class="modal-foot"
        >
          <slot name="footer" />
        </div>
      </div>
    </div>
  </Teleport>
</template>
