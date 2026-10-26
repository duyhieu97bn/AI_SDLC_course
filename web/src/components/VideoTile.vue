<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { play } from '@/player/createPlayer'
import { AdaptiveController } from '@/player/AdaptiveStream'
import type { StreamPlayer } from '@/player/types'
import { liveApi, type Camera, type StreamKind, type StreamMode } from '@/services/api'
import { ApiError } from '@/services/http'
import { useRealtimeStore } from '@/stores/realtime'

/**
 * Một ô video (US-08, US-09). Chọn luồng: lưới 9/16 hoặc chế độ "Thấp" dùng luồng phụ; ô phóng to hoặc chế độ "Cao"
 * dùng luồng chính. Cứ 45 giây hỏi lại phiên xem: bị thu hồi quyền thì dừng phát và báo "Không có quyền" (US-32 AC2).
 * Camera mất kết nối hiển thị "Mất kết nối" kèm thời điểm thay vì màn hình đen (US-09 AC3).
 * Chế độ "Tự động" tự chuyển luồng theo băng thông (US-11); phóng to số tới 8x bằng con lăn, toàn màn hình (US-14).
 */
const props = defineProps<{
  camera: Camera
  gridSize: number
  mode: StreamMode
  zoomed: boolean
  selected: boolean
}>()
defineEmits<{ select: []; zoom: [] }>()
const { t, d } = useI18n()
const realtime = useRealtimeStore()

const video = ref<HTMLVideoElement | null>(null)
const state = ref<'connecting' | 'playing' | 'denied' | 'error'>('connecting')
const currentStream = ref<StreamKind | null>(null)
const transcoded = ref(false)
const recording = ref(false)
let player: StreamPlayer | null = null
/** US-11: băng thông thiếu thì tạm dùng luồng phụ dù ô đang muốn luồng chính */
const adaptiveLow = ref(false)
let adaptive: AdaptiveController | null = null
let sampleTimer: ReturnType<typeof setInterval> | undefined
/** US-14: phóng to số 1–8x, tâm phóng theo vị trí con trỏ */
const zoom = ref(1)
const origin = ref('50% 50%')
const root = ref<HTMLDivElement | null>(null)
let renewTimer: ReturnType<typeof setInterval> | undefined
let generation = 0

const status = computed(() => {
  const e = realtime.cameraStatus[props.camera.id]
  return e ? { status: e.status, since: e.since } : { status: props.camera.status, since: props.camera.lastOfflineAt }
})
const offline = computed(() => status.value.status === 'OFFLINE')
const preferred = computed<StreamKind>(() => {
  if (props.zoomed || props.mode === 'HIGH') return 'MAIN'
  if (props.mode === 'LOW' || props.gridSize >= 9) return 'SUB'
  return 'MAIN'
})
// Chỉ chế độ "Tự động" mới tự hạ luồng (US-11 AC2: chọn "Cao" thì không tự chuyển)
const wanted = computed<StreamKind>(() =>
  props.mode === 'AUTO' && preferred.value === 'MAIN' && adaptiveLow.value ? 'SUB' : preferred.value,
)

async function start() {
  const mine = ++generation
  state.value = 'connecting' // đổi ngay, trước khi dừng luồng cũ, để không báo "đang phát" sai lúc chuyển luồng
  await stop()
  if (offline.value || !video.value) return
  try {
    const s = await liveApi.session(props.camera.id, wanted.value)
    if (mine !== generation) return
    player = (await play(video.value, { whepUrl: s.whepUrl, hlsUrl: s.hlsUrl, token: s.token }, 'auto')).player
    if (mine !== generation) {
      await player.stop()
      return
    }
    currentStream.value = s.stream
    transcoded.value = s.transcoded
    recording.value = s.recording
    state.value = 'playing'
    renewTimer = setInterval(() => void renew(), 45_000)
    startAdaptive()
  } catch (e) {
    if (mine !== generation) return
    state.value = e instanceof ApiError && e.status === 403 ? 'denied' : 'error'
  }
}

/** Kiểm tra quyền định kỳ: 403 thì dừng ngay (thu hồi quyền có hiệu lực ≤ 60 giây). */
async function renew() {
  try {
    await liveApi.session(props.camera.id, wanted.value)
  } catch (e) {
    if (e instanceof ApiError && e.status === 403) {
      await stop()
      state.value = 'denied'
    }
  }
}

function startAdaptive() {
  clearInterval(sampleTimer)
  if (props.mode !== 'AUTO' || preferred.value !== 'MAIN') {
    adaptive = null
    return
  }
  adaptive ??= new AdaptiveController(wanted.value, 4000)
  sampleTimer = setInterval(async () => {
    const s = await player?.sample?.().catch(() => null)
    if (!s || !adaptive) return
    const next = adaptive.sample(s, performance.now())
    if (next) adaptiveLow.value = next === 'SUB'
  }, 1000)
}

function onWheel(e: WheelEvent) {
  const r = root.value?.getBoundingClientRect()
  if (!r) return
  origin.value = `${((e.clientX - r.left) / r.width) * 100}% ${((e.clientY - r.top) / r.height) * 100}%`
  zoom.value = Math.min(8, Math.max(1, Math.round((zoom.value * (e.deltaY < 0 ? 1.25 : 0.8)) * 100) / 100))
}

function setZoom(z: number) {
  zoom.value = Math.min(8, Math.max(1, z))
}

async function fullscreen() {
  if (document.fullscreenElement) await document.exitFullscreen()
  else await root.value?.requestFullscreen()
}

async function stop() {
  clearInterval(renewTimer)
  clearInterval(sampleTimer)
  const p = player
  player = null
  await p?.stop()
}

/** Chụp khung hình hiện tại thành PNG; tên file có tên camera và thời điểm (US-14). */
function snapshot() {
  const v = video.value
  if (!v || !v.videoWidth) return
  const c = document.createElement('canvas')
  c.width = v.videoWidth
  c.height = v.videoHeight
  c.getContext('2d')?.drawImage(v, 0, 0)
  const stamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19)
  const a = document.createElement('a')
  a.href = c.toDataURL('image/png')
  a.download = `${props.camera.name.replace(/[\\/:*?"<>|]/g, '_')}_${stamp}.png`
  a.click()
}
defineExpose({ snapshot, fullscreen, setZoom, zoom })

watch(() => [props.camera.id, props.mode], () => {
  adaptive = null
  adaptiveLow.value = false
})
watch([() => props.camera.id, wanted, offline], () => void start())
watch(video, (v) => v && void start(), { once: true })
onBeforeUnmount(() => {
  generation++
  void stop()
})
</script>

<template>
  <div
    ref="root"
    :class="['tile', { selected, offline }]"
    data-testid="video-tile"
    :data-camera="camera.id"
    :data-state="offline ? 'offline' : state"
    :data-stream="currentStream ?? ''"
    :data-zoom="zoom"
    :data-adaptive="adaptiveLow ? 'low' : ''"
    @click="$emit('select')"
    @dblclick="$emit('zoom')"
    @wheel.prevent="onWheel"
  >
    <video
      ref="video"
      class="scene"
      :style="{ width: '100%', height: '100%', objectFit: 'cover', background: 'var(--video-bg)', transform: `scale(${zoom})`, transformOrigin: origin }"
      muted
      playsinline
      autoplay
    />
    <div
      v-if="offline"
      class="state"
    >
      {{ t('live.offline') }}<template v-if="status.since">
        · {{ t('cameras.since', { time: d(new Date(status.since), 'time') }) }}
      </template>
    </div>
    <div
      v-else-if="state !== 'playing'"
      class="state"
    >
      {{ t(`live.state.${state}`) }}
    </div>
    <div class="overlay-top">
      <span class="name">{{ camera.name }}</span>
      <span
        v-if="currentStream === 'SUB'"
        class="chip low"
      >{{ t('live.low') }}</span>
      <span
        v-if="transcoded"
        class="chip"
      >H.264</span>
      <span
        v-if="zoom > 1"
        class="chip"
        data-testid="zoom-level"
      >{{ zoom.toFixed(1) }}x</span>
      <span
        v-if="recording"
        class="rec"
        data-testid="rec"
      >REC</span>
    </div>
  </div>
</template>
