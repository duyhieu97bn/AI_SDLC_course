<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import AppModal from '@/components/AppModal.vue'
import { exportApi } from '@/services/api'
import { useToastStore } from '@/stores/toast'

/**
 * Xuất clip (US-20, design/playback.html): chọn khoảng ≤ 60 phút quanh thời điểm đang xem. Clip được xử lý nền;
 * người dùng nhận thông báo khi sẵn sàng và tải ở trang "Clip đã xuất".
 */
const props = defineProps<{ cameraId: string; cameraName: string; at: Date }>()
const emit = defineEmits<{ close: []; created: [] }>()
const { t } = useI18n()
const toast = useToastStore()
const MAX_MINUTES = 60

const toLocal = (x: Date) => new Date(x.getTime() - x.getTimezoneOffset() * 60_000).toISOString().slice(0, 19)
const from = ref(toLocal(new Date(props.at.getTime() - 2 * 60_000)))
const to = ref(toLocal(new Date(props.at.getTime() + 3 * 60_000)))
const busy = ref(false)

const minutes = computed(() => (new Date(to.value).getTime() - new Date(from.value).getTime()) / 60_000)
const problem = computed(() => {
  if (!(minutes.value > 0)) return t('exports.invalidRange')
  if (minutes.value > MAX_MINUTES) return t('exports.tooLong', { n: MAX_MINUTES })
  return ''
})

async function submit() {
  if (problem.value) return
  busy.value = true
  try {
    await exportApi.create(props.cameraId, new Date(from.value).toISOString(), new Date(to.value).toISOString())
    toast.ok(t('exports.queued'))
    emit('created')
    emit('close')
  } catch (e) {
    toast.error(e)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <AppModal
    :title="t('exports.exportClip')"
    size="sm"
    @close="emit('close')"
  >
    <p class="small muted">
      {{ cameraName }}
    </p>
    <div class="field">
      <label for="ex-from">{{ t('audit.from') }}</label>
      <input
        id="ex-from"
        v-model="from"
        class="input"
        type="datetime-local"
        step="1"
        data-testid="ex-from"
      >
    </div>
    <div class="field">
      <label for="ex-to">{{ t('audit.to') }}</label>
      <input
        id="ex-to"
        v-model="to"
        class="input"
        type="datetime-local"
        step="1"
        data-testid="ex-to"
      >
    </div>
    <p
      v-if="problem"
      class="small"
      style="color: var(--danger)"
      data-testid="ex-problem"
    >
      {{ problem }}
    </p>
    <p
      v-else
      class="small muted"
    >
      {{ t('exports.hint', { n: Math.round(minutes * 10) / 10 }) }}
    </p>
    <template #footer>
      <button
        class="btn"
        @click="emit('close')"
      >
        {{ t('common.cancel') }}
      </button>
      <button
        class="btn btn-primary"
        :disabled="!!problem || busy"
        data-testid="ex-submit"
        @click="submit"
      >
        {{ t('exports.export') }}
      </button>
    </template>
  </AppModal>
</template>
