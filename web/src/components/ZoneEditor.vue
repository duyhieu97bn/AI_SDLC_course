<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import VideoTile from '@/components/VideoTile.vue'
import { zoneApi, type Camera, type Point, type ZonesConfig } from '@/services/api'
import { useToastStore } from '@/stores/toast'

/**
 * Công cụ vẽ vùng chuyển động (lưới ô hoặc đa giác, độ nhạy) và vùng che riêng tư trên hình trực tiếp (US-26,
 * design/camera-detail.html). Tọa độ chuẩn hóa 0..1; giới hạn số vùng/điểm lấy từ khả năng camera.
 */
const props = defineProps<{ camera: Camera }>()
const { t } = useI18n()
const toast = useToastStore()

type Layer = 'motion' | 'masks'
const cfg = ref<ZonesConfig | null>(null)
const layer = ref<Layer>('motion')
const mode = ref<'CELL' | 'POLYGON'>('CELL')
const cells = ref<boolean[][]>([])
const polygons = ref<Point[][]>([])
const masks = ref<Point[][]>([])
const draft = ref<Point[]>([])
const sensitivity = ref(50)
const painting = ref<boolean | null>(null)
const svg = ref<SVGSVGElement | null>(null)

const grid = computed(() => cfg.value?.motion.grid ?? null)
const maxPolygons = computed(() => cfg.value?.motion.maxPolygons ?? null)
const maxMasks = computed(() => cfg.value?.masks.maxMasks ?? null)
const maxPoints = computed(() => (layer.value === 'masks' ? cfg.value?.masks.maxPoints : null) ?? 64)
const shapes = computed(() => (layer.value === 'masks' ? masks.value : polygons.value))
const shapeLimit = computed(() => (layer.value === 'masks' ? maxMasks.value : maxPolygons.value))
const limitReached = computed(() => shapeLimit.value != null && shapes.value.length >= shapeLimit.value)
const drawing = computed(() => layer.value === 'masks' || mode.value === 'POLYGON')

async function load() {
  try {
    const c = await zoneApi.get(props.camera.id)
    cfg.value = c
    mode.value = c.motion.mode ?? 'CELL'
    cells.value = c.motion.cells?.map((r) => [...r]) ?? []
    polygons.value = c.motion.polygons.map((p) => [...p])
    masks.value = c.masks.items.map((m) => [...m.points])
    sensitivity.value = c.motion.sensitivity ?? 50
    if (!c.motion.supported && c.masks.supported) layer.value = 'masks'
  } catch (e) {
    toast.error(e)
  }
}

function pointOf(ev: MouseEvent): Point {
  const r = svg.value!.getBoundingClientRect()
  const clamp = (v: number) => Math.min(1, Math.max(0, Math.round(v * 1000) / 1000))
  return { x: clamp((ev.clientX - r.left) / r.width), y: clamp((ev.clientY - r.top) / r.height) }
}

function cellAt(p: Point): [number, number] | null {
  if (!grid.value) return null
  return [Math.min(grid.value.rows - 1, Math.floor(p.y * grid.value.rows)), Math.min(grid.value.columns - 1, Math.floor(p.x * grid.value.columns))]
}

function down(ev: MouseEvent) {
  const p = pointOf(ev)
  if (!drawing.value) {
    const c = cellAt(p)
    if (!c) return
    painting.value = !cells.value[c[0]]![c[1]]
    cells.value[c[0]]![c[1]] = painting.value
    return
  }
  if (limitReached.value) {
    toast.push('warn', t('zones.limit', { n: shapeLimit.value }))
    return
  }
  if (draft.value.length >= maxPoints.value) {
    toast.push('warn', t('zones.maxPoints', { n: maxPoints.value }))
    return
  }
  draft.value.push(p)
}

function move(ev: MouseEvent) {
  if (painting.value === null || drawing.value) return
  const c = cellAt(pointOf(ev))
  if (c) cells.value[c[0]]![c[1]] = painting.value
}

function closeShape() {
  if (draft.value.length < 3) return
  shapes.value.push(draft.value)
  draft.value = []
}

function removeShape(i: number) {
  shapes.value.splice(i, 1)
}

function fillCells(v: boolean) {
  cells.value = cells.value.map((r) => r.map(() => v))
}

async function save() {
  try {
    if (layer.value === 'motion') {
      cfg.value =
        mode.value === 'CELL'
          ? await zoneApi.setMotion(props.camera.id, { mode: 'CELL', cells: cells.value, sensitivity: sensitivity.value })
          : await zoneApi.setMotion(props.camera.id, { mode: 'POLYGON', polygons: polygons.value, sensitivity: sensitivity.value })
    } else {
      cfg.value = await zoneApi.setMasks(props.camera.id, masks.value.map((points) => ({ points, enabled: true })))
    }
    toast.ok(t('common.saved'))
  } catch (e) {
    toast.error(e)
  }
}

const pts = (p: Point[]) => p.map((x) => `${x.x * 100},${x.y * 100}`).join(' ')
onMounted(load)
</script>

<template>
  <section class="card">
    <div class="card-head">
      <div
        class="btn-group"
        role="group"
      >
        <button
          :class="{ active: layer === 'motion' }"
          :disabled="!cfg?.motion.supported"
          data-testid="layer-motion"
          @click="layer = 'motion'; draft = []"
        >
          {{ t('zones.motion') }}
        </button>
        <button
          :class="{ active: layer === 'masks' }"
          :disabled="!cfg?.masks.supported"
          data-testid="layer-masks"
          @click="layer = 'masks'; draft = []"
        >
          {{ t('zones.masks') }}
        </button>
      </div>
      <div
        v-if="layer === 'motion'"
        class="btn-group"
        role="group"
      >
        <button
          :class="{ active: mode === 'CELL' }"
          :disabled="!grid"
          data-testid="mode-cell"
          @click="mode = 'CELL'"
        >
          {{ t('zones.cell') }}
        </button>
        <button
          :class="{ active: mode === 'POLYGON' }"
          data-testid="mode-polygon"
          @click="mode = 'POLYGON'"
        >
          {{ t('zones.polygon') }}
        </button>
      </div>
      <span class="spacer" />
      <span
        v-if="drawing"
        class="small muted"
        data-testid="zone-count"
      >{{ t('zones.count', { n: shapes.length, max: shapeLimit ?? '∞' }) }}</span>
    </div>
    <div
      v-if="cfg && !cfg.motion.supported && !cfg.masks.supported"
      class="card-body muted"
    >
      {{ t('zones.notSupported') }}
    </div>
    <div
      v-else-if="cfg"
      class="card-body stack"
    >
      <div class="zone-stage">
        <VideoTile
          :camera="camera"
          :grid-size="4"
          mode="AUTO"
          :zoomed="false"
          :selected="false"
        />
        <svg
          ref="svg"
          class="zone-overlay"
          viewBox="0 0 100 100"
          preserveAspectRatio="none"
          data-testid="zone-canvas"
          @mousedown.prevent="down"
          @mousemove="move"
          @mouseup="painting = null"
          @mouseleave="painting = null"
          @dblclick.prevent="closeShape"
        >
          <template v-if="layer === 'motion' && mode === 'CELL' && grid">
            <template
              v-for="(row, r) in cells"
              :key="r"
            >
              <rect
                v-for="(on, c) in row"
                :key="c"
                :x="(c * 100) / grid.columns"
                :y="(r * 100) / grid.rows"
                :width="100 / grid.columns"
                :height="100 / grid.rows"
                :class="['cell', { on }]"
              />
            </template>
          </template>
          <polygon
            v-for="(p, i) in shapes"
            :key="i"
            :points="pts(p)"
            :class="layer === 'masks' ? 'mask' : 'zone'"
            data-testid="zone-shape"
          />
          <polyline
            v-if="draft.length"
            :points="pts(draft)"
            class="draft"
          />
        </svg>
      </div>
      <p class="small muted">
        {{ drawing ? t('zones.drawHint') : t('zones.cellHint') }}
      </p>
      <div class="row">
        <button
          v-if="drawing"
          class="btn btn-sm"
          :disabled="draft.length < 3"
          data-testid="close-shape"
          @click="closeShape"
        >
          {{ t('zones.close') }}
        </button>
        <button
          v-if="drawing && draft.length"
          class="btn btn-sm"
          @click="draft = []"
        >
          {{ t('common.cancel') }}
        </button>
        <template v-if="!drawing">
          <button
            class="btn btn-sm"
            @click="fillCells(true)"
          >
            {{ t('zones.all') }}
          </button>
          <button
            class="btn btn-sm"
            @click="fillCells(false)"
          >
            {{ t('zones.none') }}
          </button>
        </template>
        <span
          v-for="(_, i) in drawing ? shapes : []"
          :key="i"
          class="badge badge-muted no-dot"
        >
          #{{ i + 1 }}
          <a
            href="#"
            :aria-label="t('common.delete')"
            @click.prevent="removeShape(i)"
          >×</a>
        </span>
        <span class="spacer" />
        <template v-if="layer === 'motion'">
          <label for="sens">{{ t('zones.sensitivity') }}</label>
          <input
            id="sens"
            v-model.number="sensitivity"
            type="range"
            min="0"
            max="100"
            data-testid="sensitivity"
          >
          <b style="width: 32px">{{ sensitivity }}</b>
        </template>
        <button
          class="btn btn-primary"
          data-testid="save-zones"
          @click="save"
        >
          {{ t('common.save') }}
        </button>
      </div>
    </div>
  </section>
</template>

<style scoped>
.zone-stage {
  position: relative;
  aspect-ratio: 16 / 9;
  max-width: 880px;
  background: #000;
  border-radius: 8px;
  overflow: hidden;
}
.zone-stage :deep(.tile) {
  position: absolute;
  inset: 0;
}
.zone-overlay {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  cursor: crosshair;
  user-select: none;
}
.cell {
  fill: transparent;
  stroke: rgba(255, 255, 255, 0.15);
  stroke-width: 0.1;
}
.cell.on {
  fill: rgba(37, 99, 235, 0.35);
}
.zone {
  fill: rgba(37, 99, 235, 0.3);
  stroke: #60a5fa;
  stroke-width: 0.4;
}
.mask {
  fill: rgba(0, 0, 0, 0.85);
  stroke: #f59e0b;
  stroke-width: 0.4;
}
.draft {
  fill: none;
  stroke: #fbbf24;
  stroke-width: 0.5;
  stroke-dasharray: 1 1;
}
</style>
