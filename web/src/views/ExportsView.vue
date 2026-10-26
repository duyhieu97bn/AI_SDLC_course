<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import AppIcon from '@/components/AppIcon.vue'
import { exportApi, type ExportJob, type ExportStatus } from '@/services/api'
import { useRealtimeStore } from '@/stores/realtime'
import { useToastStore } from '@/stores/toast'

/**
 * Clip đã xuất trong 7 ngày (US-20): trạng thái cập nhật qua WebSocket; tải bằng liên kết ký 5 phút; clip quá 24 giờ
 * hiển thị "Hết hạn" và phải xuất lại.
 */
const { t } = useI18n()
const toast = useToastStore()
const realtime = useRealtimeStore()
const items = ref<ExportJob[]>([])

const BADGE: Record<ExportStatus, string> = {
  QUEUED: 'badge-muted',
  PROCESSING: 'badge-info',
  READY: 'badge-ok',
  FAILED: 'badge-danger',
  EXPIRED: 'badge-warn',
}

async function load() {
  try {
    items.value = await exportApi.mine()
  } catch (e) {
    toast.error(e)
  }
}

async function download(j: ExportJob) {
  try {
    const link = await exportApi.link(j.id)
    window.location.assign(link.url)
  } catch (e) {
    toast.error(e)
    await load()
  }
}

function size(b: number | null) {
  return b == null ? '' : `${(b / 1_048_576).toFixed(1)} MB`
}

watch(
  () => realtime.exportUpdate,
  (u) => {
    if (!u) return
    const i = items.value.findIndex((x) => x.id === u.id)
    if (i >= 0) items.value.splice(i, 1, u)
    else items.value.unshift(u)
  },
)
// Dự phòng khi lỡ cập nhật WebSocket (mới tải trang, mất kết nối): hỏi lại khi còn clip đang chờ/xử lý
let timer: number | undefined
onMounted(() => {
  void load()
  timer = window.setInterval(() => {
    if (items.value.some((j) => j.status === 'QUEUED' || j.status === 'PROCESSING')) void load()
  }, 5000)
})
onBeforeUnmount(() => window.clearInterval(timer))
</script>

<template>
  <section class="card">
    <div class="card-head">
      <h3>{{ t('exports.title') }}</h3>
      <span class="spacer" />
      <span class="small muted">{{ t('exports.retentionHint') }}</span>
      <button
        class="icon-btn"
        :title="t('common.refresh')"
        @click="load"
      >
        <AppIcon name="refresh" />
      </button>
    </div>
    <div class="table-wrap">
      <table class="table">
        <thead>
          <tr>
            <th>{{ t('exports.camera') }}</th>
            <th>{{ t('exports.range') }}</th>
            <th>{{ t('exports.status') }}</th>
            <th>{{ t('exports.size') }}</th>
            <th />
          </tr>
        </thead>
        <tbody>
          <tr v-if="items.length === 0">
            <td
              colspan="5"
              class="muted"
            >
              {{ t('exports.empty') }}
            </td>
          </tr>
          <tr
            v-for="j in items"
            :key="j.id"
            data-testid="export-row"
          >
            <td>{{ j.cameraName }}</td>
            <td class="small">
              {{ new Date(j.startAt).toLocaleString() }} – {{ new Date(j.endAt).toLocaleTimeString() }}
            </td>
            <td>
              <span
                :class="['badge', BADGE[j.status]]"
                data-testid="export-status"
              >{{ t(`exports.status${j.status}`) }}</span>
              <div
                v-if="j.status === 'FAILED'"
                class="small muted"
              >
                {{ j.errorMessage }}
              </div>
            </td>
            <td class="small">
              {{ size(j.sizeBytes) }}
            </td>
            <td>
              <button
                v-if="j.status === 'READY'"
                class="btn btn-sm btn-primary"
                data-testid="export-download"
                @click="download(j)"
              >
                <AppIcon
                  name="download"
                  small
                />{{ t('exports.download') }}
              </button>
              <RouterLink
                v-else-if="j.status === 'EXPIRED' || j.status === 'FAILED'"
                class="btn btn-sm"
                :to="`/playback?camera=${j.cameraId}`"
              >
                {{ t('exports.again') }}
              </RouterLink>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>
