<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { cameraApi, orgApi, systemApi, type Camera, type SiteNode, type SystemOverview } from '@/services/api'
import { useAuthStore } from '@/stores/auth'
import { useToastStore } from '@/stores/toast'
import { useRealtimeStore } from '@/stores/realtime'

/**
 * Tổng quan (design/dashboard.html, US-39): camera trực tuyến, cảnh báo đang mở, lưu trữ, sao lưu gần nhất cho Admin
 * (MANAGE_SYSTEM); trạng thái camera theo địa điểm cho mọi người được vào trang.
 */
const { t } = useI18n()
const toast = useToastStore()
const realtime = useRealtimeStore()
const auth = useAuthStore()
const sites = ref<SiteNode[]>([])
const overview = ref<SystemOverview | null>(null)
const loaded = ref<Camera[]>([])
// Trạng thái realtime đè lên dữ liệu đã tải
const cameras = computed(() =>
  loaded.value.map((c) => {
    const e = realtime.cameraStatus[c.id]
    return e ? { ...c, status: e.status } : c
  }),
)
const perSite = computed(() =>
  sites.value.map((s) => {
    const cams = cameras.value.filter((c) => c.siteId === s.id)
    return { site: s, online: cams.filter((c) => c.status === 'ONLINE').length, total: cams.length }
  }),
)

const online = computed(() => cameras.value.filter((c) => c.status === 'ONLINE').length)
const offline = computed(() => cameras.value.filter((c) => c.status === 'OFFLINE'))
const storage = computed(() => overview.value?.storage[0] ?? null)
const lastBackup = computed(() => overview.value?.backup.postgres?.lastBackupAt ?? null)
const gb = (b: number) => `${(b / 1_073_741_824).toFixed(0)} GB`
const zones = computed(() => sites.value.reduce((n, s) => n + s.zones.length, 0))

onMounted(async () => {
  try {
    const [s, c] = await Promise.all([orgApi.sites(), cameraApi.list({ size: 1000 })])
    sites.value = s
    loaded.value = c.items
    if (auth.can('MANAGE_SYSTEM')) overview.value = await systemApi.overview()
  } catch (e) {
    toast.error(e)
  }
})
</script>

<template>
  <section class="kpis">
    <div class="card kpi">
      <span class="label">{{ t('dashboard.sites') }}</span>
      <span class="value">{{ sites.length }}</span>
      <span class="sub">{{ t('dashboard.zones', { n: zones }) }}</span>
    </div>
    <div class="card kpi">
      <span class="label">{{ t('dashboard.cameras') }}</span>
      <span class="value">{{ cameras.length }}</span>
    </div>
    <div
      v-if="overview"
      class="card kpi"
    >
      <span class="label">{{ t('dashboard.alertsOpen') }}</span>
      <RouterLink
        to="/alerts"
        class="value"
        data-testid="kpi-alerts"
        :style="{ color: overview.openAlerts ? 'var(--danger)' : 'inherit' }"
      >
        {{ overview.openAlerts }}
      </RouterLink>
    </div>
    <div
      v-if="overview"
      class="card kpi"
    >
      <span class="label">{{ t('dashboard.storage') }}</span>
      <span
        class="value"
        data-testid="kpi-storage"
        :style="{ color: storage && storage.usedPercent >= 85 ? 'var(--warning)' : 'inherit' }"
      >{{ storage ? `${storage.usedPercent}%` : '—' }}</span>
      <span
        v-if="storage"
        class="sub"
      >{{ t('dashboard.storageFree', { free: gb(storage.totalBytes - storage.usedBytes), days: storage.daysLeft ?? '—' }) }}</span>
    </div>
    <div
      v-if="overview"
      class="card kpi"
    >
      <span class="label">{{ t('dashboard.lastBackup') }}</span>
      <span
        class="value small"
        data-testid="kpi-backup"
        :style="{ fontSize: '16px', color: overview.backup.postgres?.status === 'ERROR' ? 'var(--danger)' : 'inherit' }"
      >{{ lastBackup ? new Date(lastBackup).toLocaleString() : t('dashboard.noBackup') }}</span>
    </div>
    <div class="card kpi">
      <span class="label">{{ t('cameras.statusONLINE') }}</span>
      <span
        class="value"
        style="color: var(--success)"
        data-testid="kpi-online"
      >{{ overview ? `${overview.camerasOnline} / ${overview.camerasTotal}` : online }}</span>
    </div>
    <div class="card kpi">
      <span class="label">{{ t('cameras.statusOFFLINE') }}</span>
      <span
        class="value"
        :style="{ color: offline.length ? 'var(--danger)' : 'inherit' }"
      >{{ offline.length }}</span>
    </div>
  </section>
  <section class="card">
    <div class="card-head">
      <h3>{{ t('dashboard.bySite') }}</h3>
    </div>
    <div class="table-wrap">
      <table class="table">
        <tbody>
          <tr
            v-for="p in perSite"
            :key="p.site.id"
            data-testid="site-status"
          >
            <td>{{ p.site.name }}</td>
            <td>
              <b :style="{ color: p.online < p.total ? 'var(--warning)' : 'var(--success)' }">{{ p.online }} / {{ p.total }}</b>
              <span class="small muted"> {{ t('dashboard.online') }}</span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
  <section class="card">
    <div class="card-head">
      <h3>{{ t('dashboard.offlineCameras') }}</h3>
      <span class="spacer" />
      <RouterLink
        class="btn btn-sm"
        to="/cameras"
      >
        {{ t('nav.cameras') }}
      </RouterLink>
    </div>
    <div class="card-body">
      <p
        v-if="offline.length === 0"
        class="muted"
      >
        {{ t('dashboard.allOnline') }}
      </p>
      <ul v-else>
        <li
          v-for="c in offline"
          :key="c.id"
        >
          {{ c.name }}
        </li>
      </ul>
    </div>
  </section>
</template>
