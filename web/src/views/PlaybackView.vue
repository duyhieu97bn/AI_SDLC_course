<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import AppIcon from '@/components/AppIcon.vue'
import AppModal from '@/components/AppModal.vue'
import ExportDialog from '@/components/ExportDialog.vue'
import SyncPlayback from '@/components/SyncPlayback.vue'
import TimelineBar from '@/components/TimelineBar.vue'
import { cameraApi, edgeApi, recordingApi, type Camera, type PlaybackSession, type Timeline } from '@/services/api'
import { play } from '@/player/createPlayer'
import type { StreamPlayer } from '@/player/types'
import { ApiError } from '@/services/http'
import { errorText } from '@/i18n'
import { useAuthStore } from '@/stores/auth'
import { useToastStore } from '@/stores/toast'

/**
 * Xem lại (US-17, US-18, US-21 AC3, design/playback.html): chọn camera, ngày trên lịch (chấm = có dữ liệu), dòng thời
 * gian thu phóng; xuất clip ≤ 60 phút (US-20, cần EXPORT_VIDEO); phát lại fMP4 qua MediaMTX; tốc độ 0,5x–16x, từng khung hình, phím tắt Space, ←/→ (±10 giây),
 * ,/. (từng khung hình). Nút "Giữ lại phục vụ điều tra" chỉ hiện khi có HOLD_EVIDENCE. Nguồn "Thiết bị" (US-22) phát
 * video trên thẻ nhớ/đầu ghi qua WebRTC/HLS; camera không hỗ trợ Profile G thì nguồn này bị vô hiệu kèm lý do.
 */
const { t, d } = useI18n()
const route = useRoute()
const auth = useAuthStore()
const toast = useToastStore()

const SPEEDS = [0.5, 1, 2, 4, 8, 16]
const ZOOMS = [24 * 60, 6 * 60, 60, 10] // phút
const cameras = ref<Camera[]>([])
const cameraId = ref<string>('')
const day = ref(new Date())
const month = ref(new Date(day.value.getFullYear(), day.value.getMonth(), 1))
const days = ref<Set<string>>(new Set())
const zoom = ref(0)
const center = ref<Date>(new Date())
const timeline = ref<Timeline | null>(null)
const session = ref<PlaybackSession | null>(null)
const video = ref<HTMLVideoElement | null>(null)
const playing = ref(false)
const speed = ref(1)
const now = ref<Date | null>(null)
const notice = ref('')
const holdOpen = ref(false)
/** US-19: chế độ xem lại đồng bộ nhiều camera */
const syncMode = ref(false)
/** US-22: nguồn video – lưu trên CamHub hay trên thiết bị */
const source = ref<'CAMHUB' | 'DEVICE'>('CAMHUB')
const edgeReason = ref('')
let edgePlayer: StreamPlayer | null = null
const deviceSupported = computed(() => !!cameras.value.find((c) => c.id === cameraId.value)?.capabilities?.edgeRecording)
const exportOpen = ref(false)
const cameraName = computed(() => cameras.value.find((c) => c.id === cameraId.value)?.name ?? '')
const hold = ref({ from: '', to: '', reason: '' })

const range = computed(() => {
  const minutes = ZOOMS[zoom.value]!
  if (minutes === 24 * 60) {
    const s = new Date(day.value.getFullYear(), day.value.getMonth(), day.value.getDate())
    return { from: s, to: new Date(s.getTime() + 86_400_000) }
  }
  const half = (minutes * 60_000) / 2
  return { from: new Date(center.value.getTime() - half), to: new Date(center.value.getTime() + half) }
})

const calendar = computed(() => {
  const first = month.value
  const offset = (first.getDay() + 6) % 7 // Thứ Hai đầu tuần
  const count = new Date(first.getFullYear(), first.getMonth() + 1, 0).getDate()
  return [...Array(offset).fill(null), ...Array.from({ length: count }, (_, i) => i + 1)] as (number | null)[]
})

function iso(dt: Date) {
  return `${dt.getFullYear()}-${String(dt.getMonth() + 1).padStart(2, '0')}-${String(dt.getDate()).padStart(2, '0')}`
}

async function loadDays() {
  if (!cameraId.value) return
  const m = `${month.value.getFullYear()}-${String(month.value.getMonth() + 1).padStart(2, '0')}`
  days.value = new Set(await recordingApi.days(cameraId.value, m).catch(() => []))
}

async function loadTimeline() {
  if (!cameraId.value) return
  try {
    if (source.value === 'DEVICE') {
      const e = await edgeApi.timeline(cameraId.value, range.value.from.toISOString(), range.value.to.toISOString())
      edgeReason.value = e.supported ? '' : (e.reason ?? '')
      timeline.value = { recordings: e.ranges.map((r) => ({ start: r.start, end: r.end })), events: [], holds: [] }
      return
    }
    timeline.value = await recordingApi.timeline(cameraId.value, range.value.from.toISOString(), range.value.to.toISOString())
  } catch (e) {
    toast.error(e)
  }
}

async function stopEdge() {
  const p = edgePlayer
  edgePlayer = null
  await p?.stop()
}

/** Phát từ thiết bị: path tạm trên MediaMTX, WebRTC (dự phòng HLS) như xem trực tiếp. */
async function seekDevice(at: Date) {
  const v = video.value
  if (!v) return
  const s = await edgeApi.open(cameraId.value, at.toISOString())
  await stopEdge()
  v.removeAttribute('src')
  session.value = { cameraId: s.cameraId, requestedAt: at.toISOString(), start: s.start, end: s.start, jumped: false, url: '', token: s.token, expiresAt: s.start }
  center.value = new Date(s.start)
  edgePlayer = (await play(v, { whepUrl: s.whepUrl, hlsUrl: s.hlsUrl, token: s.token }, 'auto')).player
}

async function seek(at: Date) {
  notice.value = ''
  if (source.value === 'DEVICE') {
    try {
      await seekDevice(at)
    } catch (e) {
      notice.value = e instanceof ApiError ? errorText(e.code, e.message) : String(e)
    }
    return
  }
  await stopEdge()
  try {
    const s = await recordingApi.playback(cameraId.value, at.toISOString())
    session.value = s
    if (s.jumped) notice.value = t('playback.jumped', { time: d(new Date(s.start), 'short') })
    center.value = new Date(s.start)
    const v = video.value
    if (v) {
      v.src = s.url
      v.playbackRate = speed.value
      await v.play().catch(() => undefined)
    }
  } catch (e) {
    notice.value = e instanceof ApiError ? errorText(e.code, e.message) : String(e)
  }
}

function onTime() {
  const v = video.value
  if (!v || !session.value) return
  now.value = new Date(new Date(session.value.start).getTime() + v.currentTime * 1000)
}

/** Phát hết phiên (tối đa 1 giờ) thì xin phiên tiếp theo từ thời điểm kết thúc. */
function onEnded() {
  if (session.value) void seek(new Date(session.value.end))
}

function toggle() {
  const v = video.value
  if (!v) return
  if (v.paused) void v.play()
  else v.pause()
}
function setSpeed(s: number) {
  speed.value = s
  if (video.value) video.value.playbackRate = s
}
function jump(seconds: number) {
  const v = video.value
  if (!v) return
  v.currentTime = Math.max(0, v.currentTime + seconds)
}
function frame(dir: 1 | -1) {
  const v = video.value
  if (!v) return
  v.pause()
  v.currentTime = Math.max(0, v.currentTime + dir / 25)
}

function onKey(e: KeyboardEvent) {
  if ((e.target as HTMLElement).closest('input, textarea, select')) return
  if (e.key === ' ') {
    e.preventDefault()
    toggle()
  } else if (e.key === 'ArrowRight') jump(10)
  else if (e.key === 'ArrowLeft') jump(-10)
  else if (e.key === '.') frame(1)
  else if (e.key === ',') frame(-1)
}

function pickDay(n: number) {
  day.value = new Date(month.value.getFullYear(), month.value.getMonth(), n)
  zoom.value = 0
  void loadTimeline()
}
function shiftMonth(delta: number) {
  month.value = new Date(month.value.getFullYear(), month.value.getMonth() + delta, 1)
  void loadDays()
}

function openHold() {
  const c = now.value ?? new Date()
  const toLocal = (x: Date) => new Date(x.getTime() - x.getTimezoneOffset() * 60_000).toISOString().slice(0, 16)
  hold.value = { from: toLocal(new Date(c.getTime() - 15 * 60_000)), to: toLocal(new Date(c.getTime() + 15 * 60_000)), reason: '' }
  holdOpen.value = true
}
async function saveHold() {
  try {
    await recordingApi.hold(cameraId.value, new Date(hold.value.from).toISOString(), new Date(hold.value.to).toISOString(), hold.value.reason)
    holdOpen.value = false
    toast.ok(t('playback.held'))
    await loadTimeline()
  } catch (e) {
    toast.error(e)
  }
}

watch(source, async () => {
  session.value = null
  await stopEdge()
  await loadTimeline()
})
watch(cameraId, async () => {
  if (!deviceSupported.value) source.value = 'CAMHUB'
  session.value = null
  notice.value = ''
  await Promise.all([loadDays(), loadTimeline()])
})
watch([zoom, center], () => void loadTimeline())

onMounted(async () => {
  window.addEventListener('keydown', onKey)
  try {
    cameras.value = (await cameraApi.list({ size: 1000 })).items
    const wanted = typeof route.query.camera === 'string' ? route.query.camera : null
    cameraId.value = wanted && cameras.value.some((c) => c.id === wanted) ? wanted : (cameras.value[0]?.id ?? '')
    if (typeof route.query.at === 'string') {
      const at = new Date(route.query.at)
      day.value = at
      month.value = new Date(at.getFullYear(), at.getMonth(), 1)
      await seek(at)
    }
  } catch (e) {
    toast.error(e)
  }
})
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKey)
  void stopEdge()
})
</script>

<template>
  <div class="pb-layout">
    <section class="stack">
      <div class="card">
        <div class="card-head">
          <h3>{{ t('nav.cameras') }}</h3>
        </div>
        <div class="card-body">
          <select
            v-model="cameraId"
            class="input"
            :aria-label="t('playback.camera')"
            data-testid="pb-camera"
          >
            <option
              v-for="c in cameras"
              :key="c.id"
              :value="c.id"
            >
              {{ c.name }}
            </option>
          </select>
          <div
            class="btn-group"
            role="group"
            style="margin-top: 10px"
            :aria-label="t('playback.source')"
          >
            <button
              :class="{ active: source === 'CAMHUB' }"
              data-testid="source-camhub"
              @click="source = 'CAMHUB'"
            >
              {{ t('playback.sourceCAMHUB') }}
            </button>
            <button
              :class="{ active: source === 'DEVICE' }"
              :disabled="!deviceSupported"
              :title="deviceSupported ? '' : t('playback.deviceUnsupported')"
              data-testid="source-device"
              @click="source = 'DEVICE'"
            >
              {{ t('playback.sourceDEVICE') }}
            </button>
          </div>
          <label
            class="row small"
            style="margin-top: 10px"
          >
            <input
              v-model="syncMode"
              type="checkbox"
              data-testid="sync-mode"
            >{{ t('sync.mode') }}
          </label>
          <p
            v-if="!deviceSupported || edgeReason"
            class="small muted"
            data-testid="source-reason"
          >
            {{ edgeReason || t('playback.deviceUnsupported') }}
          </p>
        </div>
      </div>
      <div class="card">
        <div class="card-head">
          <h3>{{ t('playback.month', { m: month.getMonth() + 1, y: month.getFullYear() }) }}</h3>
          <span class="spacer" />
          <button
            class="icon-btn"
            :aria-label="t('playback.prevMonth')"
            @click="shiftMonth(-1)"
          >
            <AppIcon name="left" />
          </button>
          <button
            class="icon-btn"
            :aria-label="t('playback.nextMonth')"
            @click="shiftMonth(1)"
          >
            <AppIcon name="right" />
          </button>
        </div>
        <div class="card-body">
          <div class="calendar">
            <span
              v-for="w in ['T2', 'T3', 'T4', 'T5', 'T6', 'T7', 'CN']"
              :key="w"
              class="dow"
            >{{ w }}</span>
            <span
              v-for="(n, i) in calendar"
              :key="i"
              :class="['day', { has: n && days.has(iso(new Date(month.getFullYear(), month.getMonth(), n))), today: n && iso(day) === iso(new Date(month.getFullYear(), month.getMonth(), n)) }]"
              :role="n ? 'button' : undefined"
              @click="n && pickDay(n)"
            >{{ n ?? '' }}</span>
          </div>
          <p
            class="small muted"
            style="margin-top: 8px"
          >
            {{ t('playback.calendarHint') }}
          </p>
        </div>
      </div>
    </section>

    <SyncPlayback
      v-if="syncMode"
      :cameras="cameras"
      :from="range.from"
      :to="range.to"
    />
    <section
      v-else
      class="stack"
    >
      <div class="card">
        <div
          class="card-body stack"
          style="padding: 10px"
        >
          <div class="video-grid g1">
            <div class="tile">
              <video
                ref="video"
                class="scene"
                style="width: 100%; height: 100%; object-fit: contain; background: var(--video-bg)"
                muted
                playsinline
                data-testid="pb-video"
                @timeupdate="onTime"
                @play="playing = true"
                @pause="playing = false"
                @ended="onEnded"
              />
              <div
                v-if="!session"
                class="state"
              >
                {{ t('playback.pick') }}
              </div>
            </div>
          </div>
          <div
            v-if="notice"
            class="alert-box info small"
            data-testid="pb-notice"
          >
            {{ notice }}
          </div>
          <div class="controls">
            <button
              class="icon-btn"
              :title="t('playback.frameBack')"
              :aria-label="t('playback.frameBack')"
              @click="frame(-1)"
            >
              <AppIcon name="stepBack" />
            </button>
            <button
              class="btn btn-primary btn-sm"
              :disabled="!session"
              data-testid="pb-toggle"
              @click="toggle"
            >
              <AppIcon
                :name="playing ? 'pause' : 'play'"
                small
              />{{ playing ? t('playback.pause') : t('playback.play') }}
            </button>
            <button
              class="icon-btn"
              :title="t('playback.frameFwd')"
              :aria-label="t('playback.frameFwd')"
              @click="frame(1)"
            >
              <AppIcon name="stepFwd" />
            </button>
            <div
              class="btn-group"
              role="group"
              :aria-label="t('playback.speed')"
            >
              <button
                v-for="s in SPEEDS"
                :key="s"
                :class="{ active: speed === s }"
                :data-testid="`speed-${s}`"
                @click="setSpeed(s)"
              >
                {{ s }}x
              </button>
            </div>
            <span
              class="spacer"
              style="flex: 1"
            />
            <span
              class="mono"
              data-testid="pb-time"
            >{{ now ? d(now, 'short') + ':' + String(now.getSeconds()).padStart(2, '0') : '—' }}</span>
          </div>
        </div>
      </div>
      <div class="card">
        <div class="card-head">
          <h3>{{ t('playback.timeline') }}</h3>
          <span class="spacer" />
          <div
            class="btn-group"
            role="group"
          >
            <button
              v-for="(z, i) in ZOOMS"
              :key="z"
              :class="{ active: zoom === i }"
              @click="zoom = i"
            >
              {{ z >= 60 ? `${z / 60} ${t('playback.hours')}` : `${z} ${t('playback.minutes')}` }}
            </button>
          </div>
          <button
            v-if="auth.can('EXPORT_VIDEO')"
            class="btn btn-sm"
            data-testid="pb-export"
            :disabled="!cameraId"
            @click="exportOpen = true"
          >
            <AppIcon
              name="scissors"
              small
            />{{ t('exports.exportClip') }}
          </button>
          <button
            v-if="auth.can('HOLD_EVIDENCE')"
            class="btn btn-sm"
            data-testid="pb-hold"
            :disabled="!cameraId"
            @click="openHold"
          >
            <AppIcon
              name="lock"
              small
            />{{ t('playback.hold') }}
          </button>
        </div>
        <div class="card-body stack">
          <TimelineBar
            :data="timeline"
            :from="range.from"
            :to="range.to"
            :cursor="now"
            @seek="seek"
          />
          <div class="legend">
            <span>{{ t('playback.legendRec') }}</span>
            <span class="l-evt">{{ t('playback.legendEvent') }}</span>
            <span class="l-gap">{{ t('playback.legendGap') }}</span>
          </div>
        </div>
      </div>
    </section>
  </div>

  <ExportDialog
    v-if="exportOpen"
    :camera-id="cameraId"
    :camera-name="cameraName"
    :at="now ?? new Date()"
    @close="exportOpen = false"
  />
  <AppModal
    v-if="holdOpen"
    :title="t('playback.hold')"
    size="sm"
    @close="holdOpen = false"
  >
    <div class="field">
      <label for="h-from">{{ t('audit.from') }}</label>
      <input
        id="h-from"
        v-model="hold.from"
        class="input"
        type="datetime-local"
      >
    </div>
    <div class="field">
      <label for="h-to">{{ t('audit.to') }}</label>
      <input
        id="h-to"
        v-model="hold.to"
        class="input"
        type="datetime-local"
      >
    </div>
    <div class="field">
      <label for="h-reason">{{ t('playback.reason') }}</label>
      <input
        id="h-reason"
        v-model="hold.reason"
        class="input"
        maxlength="500"
        placeholder="KH-2026-1015"
      >
    </div>
    <template #footer>
      <button
        class="btn"
        @click="holdOpen = false"
      >
        {{ t('common.cancel') }}
      </button>
      <button
        class="btn btn-primary"
        :disabled="!hold.reason.trim()"
        data-testid="hold-save"
        @click="saveHold"
      >
        {{ t('common.save') }}
      </button>
    </template>
  </AppModal>
</template>
