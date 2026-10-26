<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import AppIcon from '@/components/AppIcon.vue'
import { play, type PlayMode } from '@/player/createPlayer'
import { summarize, toCsv } from '@/player/stats'
import type { StreamPlayer } from '@/player/types'
import { issuePocToken } from '@/services/api'

/**
 * Trang đo PoC (TS-03.3, TS-03.6): phát N luồng cùng lúc và ghi first frame của từng luồng.
 * Demo cuối S0: phát 20 luồng WHEP và hiển thị số đo.
 */
const NFR_FIRST_FRAME_MS = 3000

interface Tile {
  path: string
  state: 'connecting' | 'playing' | 'error'
  mode: string
  firstFrameMs: number | null
  error: string
}

const { t } = useI18n()
const count = ref(20)
const mode = ref<PlayMode>('auto')
const pathsText = ref('')
const tiles = reactive<Tile[]>([])
const videos: HTMLVideoElement[] = []
const players: StreamPlayer[] = []
const running = ref(false)

const paths = computed(() => {
  const custom = pathsText.value
    .split(/\s+/)
    .map((s) => s.trim())
    .filter(Boolean)
  if (custom.length > 0) return custom.slice(0, count.value)
  return Array.from({ length: count.value }, (_, i) => `sim-${i + 1}`)
})

const gridClass = computed(() => {
  const n = tiles.length
  return n <= 1 ? 'g1' : n <= 4 ? 'g4' : n <= 9 ? 'g9' : 'g16'
})

const summary = computed(() =>
  summarize(tiles.filter((x) => x.firstFrameMs !== null).map((x) => x.firstFrameMs as number)),
)
const failed = computed(() => tiles.filter((x) => x.state === 'error').length)
const passed = computed(() => summary.value !== null && failed.value === 0 && summary.value.p95 <= NFR_FIRST_FRAME_MS)

function setVideo(el: unknown, i: number) {
  if (el instanceof HTMLVideoElement) videos[i] = el
}

async function start() {
  await stop()
  running.value = true
  tiles.splice(0, tiles.length, ...paths.value.map((p) => ({ path: p, state: 'connecting' as const, mode: '', firstFrameMs: null, error: '' })))
  await nextTick()
  await Promise.all(
    tiles.map(async (tile, i) => {
      const video = videos[i]
      if (!video) return
      try {
        const token = await issuePocToken(tile.path)
        const { player, result } = await play(video, token, mode.value)
        players.push(player)
        tile.state = 'playing'
        tile.mode = result.mode
        tile.firstFrameMs = result.firstFrameMs
      } catch (e) {
        tile.state = 'error'
        tile.error = e instanceof Error ? e.message : String(e)
      }
    }),
  )
}

async function stop() {
  const all = players.splice(0, players.length)
  await Promise.all(all.map((p) => p.stop()))
  running.value = false
}

function downloadCsv() {
  const blob = new Blob([toCsv(tiles)], { type: 'text/csv;charset=utf-8' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = `poc-first-frame-${new Date().toISOString().slice(0, 19).replace(/[:T]/g, '-')}.csv`
  a.click()
  URL.revokeObjectURL(a.href)
}

onBeforeUnmount(stop)
</script>

<template>
  <section class="card">
    <div class="card-head">
      <h2>{{ t('poc.title') }}</h2>
      <div class="spacer" />
      <span class="badge badge-info no-dot">{{ t('poc.target') }}</span>
    </div>
    <div class="card-body">
      <div class="form-grid">
        <div class="field">
          <label for="count">{{ t('poc.count') }}</label>
          <input
            id="count"
            v-model.number="count"
            class="input"
            type="number"
            min="1"
            max="64"
          >
        </div>
        <div class="field">
          <label for="mode">{{ t('poc.mode') }}</label>
          <select
            id="mode"
            v-model="mode"
            class="input"
          >
            <option value="auto">
              {{ t('poc.modeAuto') }}
            </option>
            <option value="webrtc">
              {{ t('poc.modeWebrtc') }}
            </option>
            <option value="hls">
              {{ t('poc.modeHls') }}
            </option>
          </select>
        </div>
        <div class="field full">
          <label for="paths">{{ t('poc.paths') }}</label>
          <textarea
            id="paths"
            v-model="pathsText"
            class="input"
            placeholder="sim-1 sim-2 cam-axis-01"
          />
          <span class="hint">{{ t('poc.pathsHint') }}</span>
        </div>
      </div>
    </div>
    <div class="card-head">
      <button
        class="btn btn-primary"
        data-testid="start"
        @click="start"
      >
        <AppIcon name="play" /> {{ t('poc.start') }}
      </button>
      <button
        class="btn"
        :disabled="!running"
        @click="stop"
      >
        <AppIcon name="pause" /> {{ t('poc.stop') }}
      </button>
      <div class="spacer" />
      <button
        class="btn btn-ghost"
        :disabled="tiles.length === 0"
        @click="downloadCsv"
      >
        <AppIcon name="download" /> {{ t('poc.exportCsv') }}
      </button>
    </div>
  </section>

  <section
    v-if="tiles.length > 0"
    class="kpis"
    data-testid="summary"
  >
    <div class="card kpi">
      <span class="label">{{ t('poc.ok') }}</span>
      <span class="value">{{ tiles.length - failed }} / {{ tiles.length }}</span>
      <span class="sub">{{ t('poc.failed') }}: {{ failed }}</span>
    </div>
    <div class="card kpi">
      <span class="label">{{ t('poc.firstFrame') }} · {{ t('poc.avg') }}</span>
      <span class="value">{{ summary?.avg ?? '–' }} ms</span>
    </div>
    <div class="card kpi">
      <span class="label">{{ t('poc.firstFrame') }} · {{ t('poc.p95') }}</span>
      <span class="value">{{ summary?.p95 ?? '–' }} ms</span>
      <span class="sub">{{ t('poc.max') }}: {{ summary?.max ?? '–' }} ms</span>
    </div>
    <div class="card kpi">
      <span class="label">NFR-01</span>
      <span
        class="value"
        :style="{ color: passed ? 'var(--success)' : 'var(--danger)' }"
      >{{ passed ? t('poc.pass') : t('poc.fail') }}</span>
    </div>
  </section>

  <section
    v-if="tiles.length > 0"
    :class="['video-grid', gridClass]"
  >
    <div
      v-for="(tile, i) in tiles"
      :key="tile.path + i"
      :class="['tile', { offline: tile.state === 'error' }]"
      data-testid="tile"
      :data-path="tile.path"
      :data-state="tile.state"
      :data-mode="tile.mode"
      :data-first-frame-ms="tile.firstFrameMs ?? ''"
      :data-error="tile.error"
    >
      <video
        :ref="(el) => setVideo(el, i)"
        class="scene"
        style="width: 100%; height: 100%; object-fit: cover; background: var(--video-bg)"
        muted
        playsinline
        autoplay
      />
      <div
        v-if="tile.state !== 'playing'"
        class="state"
      >
        {{ tile.state === 'connecting' ? t('poc.connecting') : tile.error }}
      </div>
      <div class="overlay-top">
        <span class="name">{{ tile.path }}</span>
        <span
          v-if="tile.mode"
          class="chip"
        >{{ tile.mode.toUpperCase() }}</span>
      </div>
      <div class="overlay-bottom">
        <span
          v-if="tile.firstFrameMs !== null"
          :class="['chip', { low: tile.firstFrameMs > NFR_FIRST_FRAME_MS }]"
        >{{ tile.firstFrameMs }} ms</span>
      </div>
    </div>
  </section>
</template>
