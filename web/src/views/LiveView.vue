<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import AppIcon from '@/components/AppIcon.vue'
import PromptDialog from '@/components/PromptDialog.vue'
import PtzPanel from '@/components/PtzPanel.vue'
import VideoTile from '@/components/VideoTile.vue'
import {
  cameraApi,
  liveApi,
  orgApi,
  type Camera,
  type LayoutTile,
  type SiteNode,
  type StreamMode,
  type ViewLayout,
} from '@/services/api'
import { useAuthStore } from '@/stores/auth'
import { useToastStore } from '@/stores/toast'

/**
 * Xem trực tiếp (US-08, US-09, US-10, US-12, design/live.html): cây camera kéo vào ô, lưới 1/4/9/16, nhấp đúp
 * phóng to (luồng chính), bố cục đã lưu (tự mở bố cục dùng gần nhất), bảng camera đang chọn với PTZ.
 */
const { t } = useI18n()
const auth = useAuthStore()
const route = useRoute()
const toast = useToastStore()

type Grid = 1 | 4 | 9 | 16
const GRIDS: Grid[] = [1, 4, 9, 16]

const sites = ref<SiteNode[]>([])
const cameras = ref<Camera[]>([])
const layouts = ref<ViewLayout[]>([])
const layoutId = ref<string>('')
const grid = ref<Grid>(4)
const slots = ref<(LayoutTile | null)[]>(Array(16).fill(null))
const selected = ref<number | null>(null)
const zoomed = ref<number | null>(null)
const filter = ref('')
const dragCamera = ref<string | null>(null)
const savePrompt = ref(false)
const tileRefs = ref<Record<number, InstanceType<typeof VideoTile> | null>>({})

const byId = computed(() => Object.fromEntries(cameras.value.map((c) => [c.id, c])))
const tree = computed(() => {
  const q = filter.value.trim().toLowerCase()
  return sites.value
    .map((s) => ({
      site: s,
      zones: s.zones
        .map((z) => ({ zone: z, cams: cameras.value.filter((c) => c.zoneId === z.id && (!q || c.name.toLowerCase().includes(q))) }))
        .filter((z) => z.cams.length),
    }))
    .filter((s) => s.zones.length)
})
const visibleSlots = computed(() =>
  zoomed.value !== null ? [zoomed.value] : Array.from({ length: grid.value }, (_, i) => i),
)
const selectedCamera = computed(() => {
  const s = selected.value === null ? null : slots.value[selected.value]
  return s ? byId.value[s.cameraId] : undefined
})
const selectedMode = computed(() => (selected.value === null ? 'AUTO' : (slots.value[selected.value]?.streamMode ?? 'AUTO')))

function place(position: number, cameraId: string) {
  const next = [...slots.value]
  next[position] = { position, cameraId, streamMode: 'AUTO' }
  slots.value = next
  selected.value = position
}

function onDrop(position: number) {
  if (dragCamera.value) place(position, dragCamera.value)
  dragCamera.value = null
}

/** Nhấp vào camera trên cây: đặt vào ô trống đầu tiên. */
function addToFirstFree(cameraId: string) {
  const free = Array.from({ length: grid.value }, (_, i) => i).find((i) => !slots.value[i])
  place(free ?? selected.value ?? 0, cameraId)
}

function removeSelected() {
  if (selected.value === null) return
  const next = [...slots.value]
  next[selected.value] = null
  slots.value = next
}

function setGrid(g: Grid) {
  grid.value = g
  zoomed.value = null
}

function setMode(m: StreamMode) {
  if (selected.value === null || !slots.value[selected.value]) return
  const next = [...slots.value]
  next[selected.value] = { ...next[selected.value]!, streamMode: m }
  slots.value = next
}

function toggleZoom(i: number) {
  zoomed.value = zoomed.value === i ? null : i
  selected.value = i
}

function applyLayout(l: ViewLayout) {
  layoutId.value = l.id
  grid.value = l.gridSize
  zoomed.value = null
  const next: (LayoutTile | null)[] = Array(16).fill(null)
  l.tiles.forEach((tile) => (next[tile.position] = tile))
  slots.value = next
  void liveApi.useLayout(l.id).catch(() => undefined)
}

function onLayoutChange(id: string) {
  const l = layouts.value.find((x) => x.id === id)
  if (l) applyLayout(l)
}

function currentTiles(): LayoutTile[] {
  return slots.value.slice(0, grid.value).filter((s): s is LayoutTile => s !== null)
}

async function saveLayout(name: string) {
  try {
    const existing = layouts.value.find((l) => l.name === name)
    const body = { name, gridSize: grid.value, tiles: currentTiles() }
    const saved = existing ? await liveApi.updateLayout(existing.id, body) : await liveApi.createLayout(body)
    layouts.value = await liveApi.layouts()
    layoutId.value = saved.id
    savePrompt.value = false
    toast.ok(t('live.layoutSaved'))
  } catch (e) {
    toast.error(e)
  }
}

function fullscreen() {
  void document.querySelector('.video-grid')?.requestFullscreen?.()
}

onMounted(async () => {
  try {
    const [s, c, l] = await Promise.all([orgApi.sites(), cameraApi.list({ size: 1000 }), liveApi.layouts()])
    sites.value = s
    cameras.value = c.items
    layouts.value = l
    const wanted = route.query.camera
    if (typeof wanted === 'string' && c.items.some((x) => x.id === wanted)) {
      // Mở từ cảnh báo (US-38 "Xem trực tiếp"): một ô với camera của cảnh báo
      setGrid(1)
      place(0, wanted)
    } else if (l[0]) {
      // US-10 AC1: mở lại bố cục dùng gần nhất (danh sách đã sắp theo lastUsedAt)
      applyLayout(l[0])
    }
  } catch (e) {
    toast.error(e)
  }
})
</script>

<template>
  <div class="live-layout">
    <section class="card live-side">
      <div class="card-head">
        <h3>{{ t('nav.cameras') }}</h3>
        <span class="spacer" />
        <span class="small muted">{{ t('live.dragHint') }}</span>
      </div>
      <div style="padding: 10px 10px 0">
        <input
          v-model="filter"
          class="input"
          :placeholder="t('live.filter')"
          :aria-label="t('live.filter')"
        >
      </div>
      <div class="tree">
        <details
          v-for="s in tree"
          :key="s.site.id"
          open
        >
          <summary>
            <AppIcon
              name="pin"
              small
            /> <span class="grow">{{ s.site.name }}</span>
          </summary>
          <div class="children">
            <details
              v-for="z in s.zones"
              :key="z.zone.id"
              open
            >
              <summary>{{ z.zone.name }} <span class="small muted">({{ z.cams.length }})</span></summary>
              <div class="children">
                <div
                  v-for="c in z.cams"
                  :key="c.id"
                  :class="['cam', { off: c.status === 'OFFLINE' }]"
                  draggable="true"
                  role="button"
                  tabindex="0"
                  data-testid="tree-camera"
                  @dragstart="dragCamera = c.id"
                  @click="addToFirstFree(c.id)"
                  @keydown.enter="addToFirstFree(c.id)"
                >
                  <span class="status" /><span class="grow">{{ c.name }}</span>
                </div>
              </div>
            </details>
          </div>
        </details>
      </div>
    </section>

    <section class="card">
      <div class="live-toolbar">
        <div
          class="btn-group"
          role="group"
          :aria-label="t('live.grid')"
        >
          <button
            v-for="g in GRIDS"
            :key="g"
            :class="{ active: grid === g }"
            :data-testid="`grid-${g}`"
            @click="setGrid(g)"
          >
            {{ g }}
          </button>
        </div>
        <select
          class="input"
          style="width: auto; max-width: 220px"
          :value="layoutId"
          :aria-label="t('live.layouts')"
          data-testid="layout-select"
          @change="onLayoutChange(($event.target as HTMLSelectElement).value)"
        >
          <option
            value=""
            disabled
          >
            {{ t('live.layouts') }}
          </option>
          <option
            v-for="l in layouts"
            :key="l.id"
            :value="l.id"
          >
            {{ l.name }}
          </option>
        </select>
        <button
          class="btn btn-sm"
          data-testid="layout-save"
          @click="savePrompt = true"
        >
          <AppIcon
            name="star"
            small
          />{{ t('live.saveLayout') }}
        </button>
        <span
          class="spacer"
          style="flex: 1"
        />
        <button
          class="btn btn-sm btn-ghost"
          @click="fullscreen"
        >
          <AppIcon
            name="maximize"
            small
          />{{ t('live.fullscreen') }}
        </button>
      </div>
      <div
        class="card-body"
        style="padding: 8px"
      >
        <div
          :class="['video-grid', zoomed !== null ? 'g1' : `g${grid}`]"
          data-testid="video-grid"
        >
          <template
            v-for="i in visibleSlots"
            :key="i"
          >
            <VideoTile
              v-if="slots[i] && byId[slots[i]!.cameraId]"
              :ref="(el) => (tileRefs[i] = el as InstanceType<typeof VideoTile> | null)"
              :camera="byId[slots[i]!.cameraId]!"
              :grid-size="zoomed !== null ? 1 : grid"
              :mode="slots[i]!.streamMode"
              :zoomed="zoomed === i"
              :selected="selected === i"
              @select="selected = i"
              @zoom="toggleZoom(i)"
              @dragover.prevent
              @drop.prevent="onDrop(i)"
            />
            <div
              v-else
              :class="['tile', 'empty', { selected: selected === i }]"
              data-testid="empty-tile"
              @click="selected = i"
              @dragover.prevent
              @drop.prevent="onDrop(i)"
            >
              <div class="state">
                {{ t('live.emptyTile') }}
              </div>
            </div>
          </template>
        </div>
        <p
          class="small muted"
          style="margin-top: 8px"
        >
          {{ t('live.hint') }}
        </p>
      </div>
    </section>

    <section class="card live-panel">
      <div class="card-head">
        <div>
          <h3 data-testid="selected-name">
            {{ selectedCamera?.name ?? t('live.noSelection') }}
          </h3>
          <span
            v-if="selectedCamera"
            class="small muted"
          >{{ [selectedCamera.manufacturer, selectedCamera.model].filter(Boolean).join(' ') }}</span>
        </div>
      </div>
      <div
        v-if="selectedCamera && selected !== null"
        class="card-body stack"
      >
        <div class="field">
          <label>{{ t('live.quality') }}</label>
          <div
            class="btn-group"
            role="group"
          >
            <button
              v-for="m in ['AUTO', 'HIGH', 'LOW'] as StreamMode[]"
              :key="m"
              :class="{ active: selectedMode === m }"
              @click="setMode(m)"
            >
              {{ t(`live.mode${m}`) }}
            </button>
          </div>
        </div>
        <div class="row">
          <button
            class="btn btn-sm"
            data-testid="snapshot"
            @click="tileRefs[selected]?.snapshot()"
          >
            <AppIcon
              name="image"
              small
            />{{ t('live.snapshot') }}
          </button>
          <button
            class="btn btn-sm"
            data-testid="tile-fullscreen"
            @click="tileRefs[selected]?.fullscreen()"
          >
            <AppIcon
              name="maximize"
              small
            />{{ t('live.tileFullscreen') }}
          </button>
        </div>
        <div
          class="row"
          :aria-label="t('live.digitalZoom')"
        >
          <span class="small muted">{{ t('live.digitalZoom') }}</span>
          <button
            class="btn btn-sm"
            data-testid="zoom-out"
            :aria-label="t('live.zoomOut')"
            @click="tileRefs[selected]?.setZoom((tileRefs[selected]?.zoom ?? 1) / 1.5)"
          >
            <AppIcon
              name="zoomOut"
              small
            />
          </button>
          <button
            class="btn btn-sm"
            data-testid="zoom-in"
            :aria-label="t('live.zoomIn')"
            @click="tileRefs[selected]?.setZoom((tileRefs[selected]?.zoom ?? 1) * 1.5)"
          >
            <AppIcon
              name="zoomIn"
              small
            />
          </button>
          <button
            class="btn btn-sm btn-ghost"
            @click="tileRefs[selected]?.setZoom(1)"
          >
            1x
          </button>
        </div>
        <div class="row">
          <button
            class="btn btn-sm btn-ghost"
            @click="removeSelected"
          >
            <AppIcon
              name="x"
              small
            />{{ t('live.removeTile') }}
          </button>
        </div>
        <!-- INV-05: bảng PTZ chỉ hiện với camera hỗ trợ PTZ và người có quyền CONTROL_PTZ -->
        <PtzPanel
          v-if="selectedCamera.capabilities?.ptz && auth.can('CONTROL_PTZ')"
          :camera-id="selectedCamera.id"
        />
      </div>
    </section>
  </div>

  <PromptDialog
    v-if="savePrompt"
    :title="t('live.saveLayout')"
    :label="t('live.layoutName')"
    :initial="layouts.find((l) => l.id === layoutId)?.name"
    @close="savePrompt = false"
    @submit="saveLayout"
  />
</template>
