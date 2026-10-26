<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import AppIcon from './AppIcon.vue'
import PromptDialog from './PromptDialog.vue'
import { liveApi, type PresetView } from '@/services/api'
import { ApiError } from '@/services/http'
import { errorText } from '@/i18n'
import { useRealtimeStore } from '@/stores/realtime'

/**
 * Bảng PTZ (US-12, US-13, design/live.html): nhấn giữ nút hoặc phím mũi tên để xoay/nghiêng, thả ra thì dừng;
 * thu/phóng; tốc độ; preset có sẵn trên camera và preset lưu từ CamHub. Người khác có thứ hạng cao hơn đang điều
 * khiển thì hiển thị "Đang được điều khiển bởi …".
 */
const props = defineProps<{ cameraId: string }>()
const { t } = useI18n()
const realtime = useRealtimeStore()

const speed = ref(4)
const lockedBy = ref<string | null>(null)
const controlling = ref(false)
const presets = ref<PresetView[]>([])
const saving = ref(false)
const error = ref('')
let moving = false

async function loadPresets() {
  try {
    presets.value = await liveApi.presets(props.cameraId)
  } catch (e) {
    error.value = e instanceof ApiError ? errorText(e.code, e.message) : String(e)
  }
}

async function guard(action: () => Promise<unknown>) {
  try {
    await action()
    lockedBy.value = null
    controlling.value = true
    error.value = ''
  } catch (e) {
    if (e instanceof ApiError && e.code === 'PTZ_LOCKED') {
      lockedBy.value = String(e.details.holder ?? '')
      controlling.value = false
    } else {
      error.value = e instanceof ApiError ? errorText(e.code, e.message) : String(e)
    }
  }
}

function start(pan: number, tilt: number, zoom: number) {
  moving = true
  const s = speed.value / 8
  void guard(() => liveApi.ptz(props.cameraId, pan * s, tilt * s, zoom * s))
}

function end() {
  if (!moving) return
  moving = false
  void guard(() => liveApi.ptzStop(props.cameraId))
}

const KEYS: Record<string, [number, number, number]> = {
  ArrowUp: [0, 1, 0],
  ArrowDown: [0, -1, 0],
  ArrowLeft: [-1, 0, 0],
  ArrowRight: [1, 0, 0],
  '+': [0, 0, 1],
  '-': [0, 0, -1],
}
function onKeyDown(e: KeyboardEvent) {
  const target = e.target as HTMLElement
  if (target.closest('input, textarea, select')) return
  const k = KEYS[e.key]
  if (k && !e.repeat) {
    e.preventDefault()
    start(...k)
  }
}
function onKeyUp(e: KeyboardEvent) {
  if (KEYS[e.key]) end()
}

const promptOpen = ref(false)
async function savePreset(name: string) {
  saving.value = true
  await guard(() => liveApi.savePreset(props.cameraId, name))
  saving.value = false
  promptOpen.value = false
  await loadPresets()
}

watch(
  () => realtime.ptzTaken,
  (e) => {
    if (e && e.cameraId === props.cameraId) {
      lockedBy.value = e.takenBy
      controlling.value = false
    }
  },
)
watch(() => props.cameraId, () => {
  lockedBy.value = null
  controlling.value = false
  void loadPresets()
})
onMounted(() => {
  void loadPresets()
  window.addEventListener('keydown', onKeyDown)
  window.addEventListener('keyup', onKeyUp)
})
onBeforeUnmount(() => {
  end()
  window.removeEventListener('keydown', onKeyDown)
  window.removeEventListener('keyup', onKeyUp)
})
</script>

<template>
  <div
    class="stack"
    data-testid="ptz-panel"
  >
    <div class="row">
      <h3>{{ t('ptz.title') }}</h3>
      <span class="spacer" />
      <span
        v-if="lockedBy"
        class="badge badge-warn"
        data-testid="ptz-locked"
      >{{ t('ptz.lockedBy', { name: lockedBy }) }}</span>
      <span
        v-else-if="controlling"
        class="badge badge-ok"
      >{{ t('ptz.controlling') }}</span>
    </div>
    <div class="ptz">
      <span />
      <button
        :aria-label="t('ptz.up')"
        data-testid="ptz-up"
        @pointerdown="start(0, 1, 0)"
        @pointerup="end"
        @pointerleave="end"
      >
        <AppIcon name="up" />
      </button>
      <span />
      <button
        :aria-label="t('ptz.left')"
        @pointerdown="start(-1, 0, 0)"
        @pointerup="end"
        @pointerleave="end"
      >
        <AppIcon name="left" />
      </button>
      <button
        class="center"
        :aria-label="t('ptz.stop')"
        @click="guard(() => liveApi.ptzStop(cameraId))"
      >
        <AppIcon name="home" />
      </button>
      <button
        :aria-label="t('ptz.right')"
        @pointerdown="start(1, 0, 0)"
        @pointerup="end"
        @pointerleave="end"
      >
        <AppIcon name="right" />
      </button>
      <span />
      <button
        :aria-label="t('ptz.down')"
        @pointerdown="start(0, -1, 0)"
        @pointerup="end"
        @pointerleave="end"
      >
        <AppIcon name="down" />
      </button>
      <span />
    </div>
    <div
      class="row"
      style="justify-content: center"
    >
      <button
        class="btn btn-sm"
        @pointerdown="start(0, 0, -1)"
        @pointerup="end"
        @pointerleave="end"
      >
        <AppIcon
          name="zoomOut"
          small
        />{{ t('ptz.zoomOut') }}
      </button>
      <button
        class="btn btn-sm"
        @pointerdown="start(0, 0, 1)"
        @pointerup="end"
        @pointerleave="end"
      >
        <AppIcon
          name="zoomIn"
          small
        />{{ t('ptz.zoomIn') }}
      </button>
    </div>
    <div class="range-row small">
      <span>{{ t('ptz.speed') }}</span>
      <input
        v-model.number="speed"
        type="range"
        min="1"
        max="8"
        :aria-label="t('ptz.speed')"
      ><span>{{ speed }}</span>
    </div>
    <div
      v-if="error"
      class="alert-box danger small"
    >
      {{ error }}
    </div>
    <div class="row">
      <h3>Preset</h3>
      <span class="spacer" />
      <button
        class="btn btn-sm"
        data-testid="preset-save"
        :disabled="saving"
        @click="promptOpen = true"
      >
        <AppIcon
          name="plus"
          small
        />{{ t('ptz.savePreset') }}
      </button>
    </div>
    <div class="list preset-list card">
      <div
        v-for="p in presets"
        :key="p.token"
        class="list-item"
        role="button"
        tabindex="0"
        data-testid="preset"
        @click="guard(() => liveApi.gotoPreset(cameraId, p.token))"
        @keydown.enter="guard(() => liveApi.gotoPreset(cameraId, p.token))"
      >
        <AppIcon
          name="star"
          small
        /><span class="grow">{{ p.name }}</span>
        <span :class="['badge', 'no-dot', p.source === 'CAMHUB' ? 'badge-info' : 'badge-muted']">
          {{ p.source === 'CAMHUB' ? 'CamHub' : t('ptz.fromCamera') }}
        </span>
      </div>
      <div
        v-if="presets.length === 0"
        class="list-item small muted"
      >
        {{ t('ptz.noPresets') }}
      </div>
    </div>
    <PromptDialog
      v-if="promptOpen"
      :title="t('ptz.savePreset')"
      :label="t('ptz.presetName')"
      @close="promptOpen = false"
      @submit="savePreset"
    />
  </div>
</template>
