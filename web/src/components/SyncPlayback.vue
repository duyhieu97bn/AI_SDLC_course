<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import TimelineBar from '@/components/TimelineBar.vue'
import { recordingApi, type Camera, type PlaybackSession, type Timeline } from '@/services/api'
import { ApiError } from '@/services/http'
import { errorText } from '@/i18n'
import { addCamera, corrections, maxDriftS, MAX_SYNC_CAMERAS } from '@/player/SyncController'
import { useToastStore } from '@/stores/toast'

/**
 * Xem lại đồng bộ (US-19): chọn tối đa 4 camera, timeline chung (hợp các khoảng có ghi), chọn một mốc thì mọi camera
 * phát từ mốc đó; mỗi 500 ms bù lệch về camera đầu tiên để lệch ≤ 1 giây.
 */
const props = defineProps<{ cameras: Camera[]; from: Date; to: Date }>()
const { t } = useI18n()
const toast = useToastStore()
const selected = ref<string[]>([])
const sessions = ref<Record<string, PlaybackSession | null>>({})
const errors = ref<Record<string, string>>({})
const videos = ref<Record<string, HTMLVideoElement | null>>({})
const timeline = ref<Timeline | null>(null)
const cursor = ref<Date | null>(null)
const drift = ref(0)
let timer: ReturnType<typeof setInterval> | undefined

const names = computed(() => Object.fromEntries(props.cameras.map((c) => [c.id, c.name])))

function toggle(id: string, on: boolean) {
  if (!on) {
    selected.value = selected.value.filter((x) => x !== id)
  } else {
    const r = addCamera(selected.value, id)
    if (!r.ok) {
      toast.push('warn', t('sync.limit', { n: MAX_SYNC_CAMERAS }))
      return
    }
    selected.value = r.list
  }
  void loadTimeline()
}

async function loadTimeline() {
  const all = await Promise.all(
    selected.value.map((id) => recordingApi.timeline(id, props.from.toISOString(), props.to.toISOString()).catch(() => null)),
  )
  timeline.value = {
    recordings: all.flatMap((x) => x?.recordings ?? []).sort((a, b) => a.start.localeCompare(b.start)),
    events: [],
    holds: [],
  }
}

async function seek(at: Date) {
  errors.value = {}
  await Promise.all(
    selected.value.map(async (id) => {
      try {
        const s = await recordingApi.playback(id, at.toISOString())
        sessions.value = { ...sessions.value, [id]: s }
        const v = videos.value[id]
        if (v) {
          v.src = s.url
          await v.play().catch(() => undefined)
        }
      } catch (e) {
        sessions.value = { ...sessions.value, [id]: null }
        errors.value = { ...errors.value, [id]: e instanceof ApiError ? errorText(e.code, e.message) : String(e) }
      }
    }),
  )
  // Phiên có thể bắt đầu ở đoạn gần nhất; đưa mọi camera về đúng mốc đã chọn
  for (const id of selected.value) {
    const s = sessions.value[id]
    const v = videos.value[id]
    if (s && v) {
      const offset = (at.getTime() - new Date(s.start).getTime()) / 1000
      if (offset > 0) v.currentTime = offset
    }
  }
  clearInterval(timer)
  timer = setInterval(sync, 500)
}

function sync() {
  const ids = selected.value.filter((id) => sessions.value[id] && videos.value[id])
  const streams = ids.map((id) => ({ startMs: new Date(sessions.value[id]!.start).getTime(), currentTime: videos.value[id]!.currentTime }))
  corrections(streams).forEach((c, i) => {
    if (c !== null) videos.value[ids[i]!]!.currentTime = c
  })
  drift.value = maxDriftS(streams)
  if (streams[0]) cursor.value = new Date(streams[0].startMs + streams[0].currentTime * 1000)
}

function playAll(play: boolean) {
  for (const id of selected.value) {
    const v = videos.value[id]
    if (!v) continue
    if (play) void v.play().catch(() => undefined)
    else v.pause()
  }
}

onBeforeUnmount(() => clearInterval(timer))
</script>

<template>
  <section class="card">
    <div class="card-head">
      <h3>{{ t('sync.title') }}</h3>
      <span class="spacer" />
      <span
        class="small muted"
        data-testid="sync-drift"
        :data-drift="drift.toFixed(2)"
      >{{ t('sync.drift', { s: drift.toFixed(2) }) }}</span>
    </div>
    <div class="card-body stack">
      <div class="row wrap">
        <label
          v-for="c in cameras"
          :key="c.id"
          class="row small"
        >
          <input
            type="checkbox"
            :checked="selected.includes(c.id)"
            :data-testid="`sync-cam-${c.name}`"
            @change="toggle(c.id, ($event.target as HTMLInputElement).checked)"
          >{{ c.name }}
        </label>
      </div>
      <div
        class="sync-grid"
        :class="`n${Math.max(1, selected.length)}`"
      >
        <div
          v-for="id in selected"
          :key="id"
          class="sync-cell"
        >
          <video
            :ref="(el) => (videos[id] = el as HTMLVideoElement | null)"
            muted
            playsinline
            :data-testid="`sync-video-${names[id]}`"
          />
          <span class="sync-name">{{ names[id] }}</span>
          <span
            v-if="errors[id]"
            class="sync-error small"
          >{{ errors[id] }}</span>
        </div>
      </div>
      <div class="row">
        <button
          class="btn btn-sm"
          data-testid="sync-play"
          @click="playAll(true)"
        >
          {{ t('playback.play') }}
        </button>
        <button
          class="btn btn-sm"
          @click="playAll(false)"
        >
          {{ t('playback.pause') }}
        </button>
      </div>
      <TimelineBar
        v-if="selected.length"
        :data="timeline"
        :from="from"
        :to="to"
        :cursor="cursor"
        @seek="seek"
      />
    </div>
  </section>
</template>

<style scoped>
.sync-grid {
  display: grid;
  gap: 6px;
  grid-template-columns: repeat(2, minmax(0, 1fr));
}
.sync-grid.n1 {
  grid-template-columns: 1fr;
}
.sync-cell {
  position: relative;
  aspect-ratio: 16 / 9;
  background: var(--video-bg);
  border-radius: 6px;
  overflow: hidden;
}
.sync-cell video {
  width: 100%;
  height: 100%;
  object-fit: contain;
}
.sync-name {
  position: absolute;
  left: 8px;
  top: 6px;
  color: #fff;
  font-size: 12px;
  text-shadow: 0 1px 2px #000;
}
.sync-error {
  position: absolute;
  inset: auto 8px 8px 8px;
  color: #fff;
}
</style>
