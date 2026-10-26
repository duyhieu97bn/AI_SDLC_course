<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import AppIcon from '@/components/AppIcon.vue'
import AlertDetail from '@/views/alerts/AlertDetail.vue'
import ChannelsTab from '@/views/alerts/ChannelsTab.vue'
import RulesTab from '@/views/alerts/RulesTab.vue'
import { useAuthStore } from '@/stores/auth'
import { alertApi, type Alert, type AlertStatus, type AlertType } from '@/services/api'
import { useRealtimeStore } from '@/stores/realtime'
import { useToastStore } from '@/stores/toast'

/**
 * Cảnh báo (design/alerts.html): danh sách đang mở/30 ngày lọc theo loại (US-34.3), chi tiết – nhận xử lý, chuyển giao,
 * đóng có kết quả, xem lại (US-37, US-38); tab Quy tắc chuyển động (Admin) và Kênh nhận của tôi (US-36).
 */
const { t } = useI18n()
const route = useRoute()
const toast = useToastStore()
const realtime = useRealtimeStore()
const status = ref<'OPEN' | 'ALL'>('OPEN')
const type = ref<AlertType | ''>('')
const items = ref<Alert[]>([])
const loaded = ref(false)
const highlight = ref(typeof route.query.id === 'string' ? route.query.id : '')

const TYPES: AlertType[] = ['MOTION', 'CAMERA_OFFLINE', 'SITE_OFFLINE', 'STORAGE_THRESHOLD']
const auth = useAuthStore()
const tab = ref<'list' | 'rules' | 'channels'>('list')
const openId = ref<string>(typeof route.query.id === 'string' ? route.query.id : '')
const STATUS_BADGE: Record<AlertStatus, string> = {
  NEW: 'badge-danger',
  IN_PROGRESS: 'badge-warn',
  RECOVERED: 'badge-ok',
  CLOSED: 'badge-muted',
}

async function load() {
  try {
    items.value = await alertApi.list(status.value, type.value || undefined)
    loaded.value = true
  } catch (e) {
    toast.error(e)
  }
}

watch([status, type], load)
// Cảnh báo mới (thông báo qua WebSocket) thì tải lại danh sách
watch(() => realtime.lastNotification, (n) => n?.alertId && void load())
watch(
  () => route.query.id,
  (id) => {
    highlight.value = typeof id === 'string' ? id : ''
    if (highlight.value) {
      status.value = 'ALL'
      openId.value = highlight.value
      tab.value = 'list'
    }
  },
)
onMounted(() => {
  if (highlight.value) status.value = 'ALL'
  void load()
})
</script>

<template>
  <div
    class="tabs"
    style="margin-bottom: 12px"
  >
    <button
      :class="['tab', { active: tab === 'list' }]"
      data-testid="tab-alerts"
      @click="tab = 'list'"
    >
      {{ t('alerts.title') }}
    </button>
    <button
      v-if="auth.can('MANAGE_SYSTEM')"
      :class="['tab', { active: tab === 'rules' }]"
      data-testid="tab-rules"
      @click="tab = 'rules'"
    >
      {{ t('rules.title') }}
    </button>
    <button
      :class="['tab', { active: tab === 'channels' }]"
      data-testid="tab-channels"
      @click="tab = 'channels'"
    >
      {{ t('channels.mine') }}
    </button>
  </div>
  <RulesTab v-if="tab === 'rules'" />
  <ChannelsTab v-else-if="tab === 'channels'" />
  <div
    v-else
    :class="openId ? 'alerts-split' : ''"
  >
    <section class="card">
      <div class="card-head">
        <div
          class="btn-group"
          role="group"
        >
          <button
            :class="{ active: status === 'OPEN' }"
            data-testid="alerts-open"
            @click="status = 'OPEN'"
          >
            {{ t('alerts.open') }}
          </button>
          <button
            :class="{ active: status === 'ALL' }"
            data-testid="alerts-all"
            @click="status = 'ALL'"
          >
            {{ t('alerts.all') }}
          </button>
        </div>
        <select
          v-model="type"
          class="select"
          style="width: auto"
          :aria-label="t('alerts.type')"
        >
          <option value="">
            {{ t('alerts.allTypes') }}
          </option>
          <option
            v-for="ty in TYPES"
            :key="ty"
            :value="ty"
          >
            {{ t(`alerts.type${ty}`) }}
          </option>
        </select>
        <span class="spacer" />
        <button
          class="icon-btn"
          :title="t('common.refresh')"
          @click="load"
        >
          <AppIcon name="refresh" />
        </button>
      </div>
      <div class="table-wrap">
        <table
          class="table"
          :data-loaded="loaded"
          data-testid="alerts-table"
        >
          <thead>
            <tr>
              <th>{{ t('alerts.time') }}</th>
              <th>{{ t('alerts.type') }}</th>
              <th>{{ t('alerts.content') }}</th>
              <th>{{ t('alerts.status') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="items.length === 0">
              <td
                colspan="4"
                class="muted"
              >
                {{ t('alerts.empty') }}
              </td>
            </tr>
            <tr
              v-for="a in items"
              :key="a.id"
              data-testid="alert-row"
              style="cursor: pointer"
              :style="a.id === (openId || highlight) ? 'background: var(--primary-weak)' : ''"
              @click="openId = a.id"
            >
              <td class="small">
                {{ new Date(a.occurredAt).toLocaleString() }}
              </td>
              <td>
                <span :class="['badge', a.severity === 'CRITICAL' ? 'badge-danger' : 'badge-warn']">
                  {{ t(`alerts.type${a.type}`) }}
                </span>
              </td>
              <td class="wrap">
                <RouterLink
                  v-if="a.cameraId"
                  :to="`/cameras/${a.cameraId}`"
                >
                  {{ a.title }}
                </RouterLink>
                <span v-else>{{ a.title }}</span>
              </td>
              <td>
                <span
                  :class="['badge', STATUS_BADGE[a.status]]"
                  data-testid="alert-status"
                >{{ t(`alerts.status${a.status}`) }}</span>
                <div
                  v-if="a.recoveredAt"
                  class="small muted"
                >
                  {{ new Date(a.recoveredAt).toLocaleString() }}
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
    <AlertDetail
      v-if="openId"
      :alert-id="openId"
      @changed="load"
      @close="openId = ''"
    />
  </div>
</template>

<style scoped>
.alerts-split {
  display: grid;
  grid-template-columns: minmax(0, 1.4fr) minmax(0, 1fr);
  gap: 12px;
  align-items: start;
}
@media (max-width: 1100px) {
  .alerts-split {
    grid-template-columns: 1fr;
  }
}
</style>
