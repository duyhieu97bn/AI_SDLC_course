<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import VideoTile from '@/components/VideoTile.vue'
import { imageApi, type Camera, type ImagingChange, type ImagingSettings } from '@/services/api'
import { useToastStore } from '@/stores/toast'

/**
 * Cấu hình hình ảnh (US-24, design/camera-detail.html tab "Hình ảnh"): kéo thanh là áp ngay để xem trước trên hình
 * trực tiếp; "Khôi phục giá trị trước" áp lại các giá trị lúc mở tab (AC1).
 */
const props = defineProps<{ camera: Camera }>()
const { t } = useI18n()
const toast = useToastStore()
const current = ref<ImagingSettings | null>(null)
const initial = ref<ImagingChange | null>(null)
const busy = ref(false)
let timer: ReturnType<typeof setTimeout> | undefined

async function load() {
  try {
    current.value = await imageApi.get(props.camera.id)
    initial.value = {
      brightness: current.value.brightness,
      contrast: current.value.contrast,
      irMode: current.value.irMode,
      wdr: current.value.wdr,
    }
  } catch (e) {
    toast.error(e)
  }
}

/** Gộp các lần kéo thanh trong 400 ms rồi mới gửi xuống camera. */
function change(c: ImagingChange) {
  if (!current.value) return
  Object.assign(current.value, c)
  clearTimeout(timer)
  timer = setTimeout(() => void apply(c), 400)
}

async function apply(c: ImagingChange) {
  busy.value = true
  try {
    current.value = await imageApi.set(props.camera.id, c)
  } catch (e) {
    toast.error(e)
  } finally {
    busy.value = false
  }
}

async function restore() {
  if (!initial.value) return
  const c: ImagingChange = { ...initial.value }
  // Chỉ gửi tham số camera có
  Object.keys(c).forEach((k) => c[k as keyof ImagingChange] == null && delete c[k as keyof ImagingChange])
  await apply(c)
  toast.ok(t('image.restored'))
}

onMounted(load)
onBeforeUnmount(() => clearTimeout(timer))
</script>

<template>
  <section class="card">
    <div class="card-body image-tab">
      <VideoTile
        :camera="camera"
        :grid-size="1"
        mode="AUTO"
        :zoomed="false"
        :selected="false"
      />
      <div
        v-if="current"
        class="stack"
      >
        <div
          v-if="current.brightness != null && current.options?.brightness"
          class="field"
        >
          <label for="img-b">{{ t('image.brightness') }}: <b data-testid="img-b-value">{{ Math.round(current.brightness) }}</b></label>
          <input
            id="img-b"
            type="range"
            :min="current.options.brightness.min"
            :max="current.options.brightness.max"
            :value="current.brightness"
            data-testid="img-brightness"
            @input="change({ brightness: Number(($event.target as HTMLInputElement).value) })"
          >
        </div>
        <div
          v-if="current.contrast != null && current.options?.contrast"
          class="field"
        >
          <label for="img-c">{{ t('image.contrast') }}: <b>{{ Math.round(current.contrast) }}</b></label>
          <input
            id="img-c"
            type="range"
            :min="current.options.contrast.min"
            :max="current.options.contrast.max"
            :value="current.contrast"
            data-testid="img-contrast"
            @input="change({ contrast: Number(($event.target as HTMLInputElement).value) })"
          >
        </div>
        <div
          v-if="current.options?.irModes?.length"
          class="field"
        >
          <label for="img-ir">{{ t('image.ir') }}</label>
          <select
            id="img-ir"
            class="select"
            :value="current.irMode ?? ''"
            data-testid="img-ir"
            @change="apply({ irMode: ($event.target as HTMLSelectElement).value as 'AUTO' | 'DAY' | 'NIGHT' })"
          >
            <option
              v-for="m in current.options.irModes"
              :key="m"
              :value="m"
            >
              {{ t(`image.ir${m}`) }}
            </option>
          </select>
        </div>
        <label
          v-if="current.options?.wdr"
          class="row"
        >
          <input
            type="checkbox"
            :checked="!!current.wdr"
            data-testid="img-wdr"
            @change="apply({ wdr: ($event.target as HTMLInputElement).checked })"
          >{{ t('image.wdr') }}
        </label>
        <div class="row">
          <button
            class="btn"
            :disabled="busy"
            data-testid="img-restore"
            @click="restore"
          >
            {{ t('image.restore') }}
          </button>
          <span
            v-if="busy"
            class="small muted"
          >{{ t('common.loading') }}</span>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.image-tab {
  display: grid;
  grid-template-columns: minmax(0, 1.4fr) minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}
.image-tab :deep(.tile) {
  aspect-ratio: 16 / 9;
}
@media (max-width: 860px) {
  .image-tab {
    grid-template-columns: 1fr;
  }
}
</style>
