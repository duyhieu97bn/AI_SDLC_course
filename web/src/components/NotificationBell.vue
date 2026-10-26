<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import AppIcon from '@/components/AppIcon.vue'
import { notificationApi, type AppNotification } from '@/services/api'
import { useRealtimeStore } from '@/stores/realtime'
import { useToastStore } from '@/stores/toast'

/**
 * Chuông thông báo trên thanh trên cùng (US-42): số chưa đọc, danh sách gần đây, toast khi có thông báo mới qua
 * WebSocket. Âm báo bật/tắt được, lưu theo trình duyệt.
 */
const { t } = useI18n()
const router = useRouter()
const realtime = useRealtimeStore()
const toast = useToastStore()
const open = ref(false)
const unread = ref(0)
const items = ref<AppNotification[]>([])
const sound = ref(readSound())

function readSound(): boolean {
  try {
    return localStorage.getItem('camhub.notifySound') !== 'off'
  } catch {
    return true
  }
}

function toggleSound() {
  sound.value = !sound.value
  try {
    localStorage.setItem('camhub.notifySound', sound.value ? 'on' : 'off')
  } catch {
    /* trình duyệt chặn lưu trữ: chỉ áp dụng trong phiên */
  }
}

function beep() {
  try {
    const ctx = new AudioContext()
    const osc = ctx.createOscillator()
    osc.frequency.value = 880
    osc.connect(ctx.destination)
    osc.start()
    osc.stop(ctx.currentTime + 0.15)
  } catch {
    /* không có âm thanh */
  }
}

async function refresh() {
  try {
    unread.value = (await notificationApi.unread()).count
    if (open.value) items.value = await notificationApi.list()
  } catch {
    /* chuông không quan trọng bằng trang đang mở */
  }
}

async function toggle() {
  open.value = !open.value
  if (open.value) items.value = await notificationApi.list()
}

async function go(n: AppNotification) {
  open.value = false
  if (!n.readAt) await notificationApi.read(n.id).catch(() => undefined)
  await refresh()
  if (n.link) await router.push(n.link)
}

async function readAll() {
  await notificationApi.readAll()
  await refresh()
}

watch(
  () => realtime.lastNotification,
  (n) => {
    if (!n) return
    unread.value++
    toast.push('warn', n.title, 6000)
    if (sound.value) beep()
    if (open.value) void refresh()
  },
)

let timer: number | undefined
onMounted(() => {
  void refresh()
  // Dự phòng khi WebSocket gián đoạn
  timer = window.setInterval(() => void refresh(), 60_000)
})
onBeforeUnmount(() => window.clearInterval(timer))
</script>

<template>
  <div :class="['dropdown', { open }]">
    <button
      class="icon-btn"
      :aria-label="t('notifications.title')"
      :title="t('notifications.title')"
      data-testid="bell"
      style="position: relative"
      @click="toggle"
    >
      <AppIcon name="bell" />
      <span
        v-if="unread > 0"
        class="bell-count"
        data-testid="bell-count"
      >{{ unread > 99 ? '99+' : unread }}</span>
    </button>
    <div
      class="dropdown-menu"
      style="min-width: 320px; max-width: 90vw"
    >
      <div class="row small">
        <b>{{ t('notifications.title') }}</b>
        <span class="spacer" />
        <a
          href="#"
          @click.prevent="toggleSound"
        >{{ sound ? t('notifications.soundOn') : t('notifications.soundOff') }}</a>
        <a
          href="#"
          data-testid="read-all"
          @click.prevent="readAll"
        >{{ t('notifications.readAll') }}</a>
      </div>
      <p
        v-if="items.length === 0"
        class="muted small"
        style="padding: 8px"
      >
        {{ t('notifications.empty') }}
      </p>
      <button
        v-for="n in items.slice(0, 12)"
        :key="n.id"
        data-testid="notification"
        @click="go(n)"
      >
        <span :class="['badge', n.readAt ? 'badge-muted' : 'badge-info', 'no-dot']">{{ n.readAt ? '' : '•' }}</span>
        <span class="grow">
          <b class="small">{{ n.title }}</b>
          <div class="small muted">{{ n.createdAt ? new Date(n.createdAt).toLocaleString() : '' }}</div>
        </span>
      </button>
    </div>
  </div>
</template>

<style scoped>
.bell-count {
  position: absolute;
  top: -2px;
  right: -2px;
  min-width: 18px;
  height: 18px;
  padding: 0 4px;
  border-radius: 9px;
  background: var(--danger);
  color: #fff;
  font-size: 11px;
  line-height: 18px;
  text-align: center;
}
</style>
