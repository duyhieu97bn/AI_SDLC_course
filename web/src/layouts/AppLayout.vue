<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import AppIcon from '@/components/AppIcon.vue'
import NotificationBell from '@/components/NotificationBell.vue'
import type { IconName } from '@/components/icons'
import { useThemeStore } from '@/stores/theme'
import { useAuthStore, type Permission } from '@/stores/auth'
import type { Locale } from '@/i18n'
import { organizationOfToken, useRealtimeStore } from '@/stores/realtime'

/** Khung trang theo design/: sidebar trái, topbar, vùng nội dung. Menu chỉ hiện mục người dùng có quyền. */
interface NavItem {
  section?: string
  to?: string
  icon?: IconName
  label?: string
  any?: Permission[]
}

const { t, locale } = useI18n()
const theme = useThemeStore()
const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const menuOpen = ref(false)

const all = computed<NavItem[]>(() => [
  { section: t('nav.monitor') },
  { to: '/dashboard', icon: 'dashboard', label: t('nav.dashboard'), any: ['MANAGE_SYSTEM', 'MANAGE_USERS', 'CONFIGURE_CAMERA'] },
  { to: '/live', icon: 'video', label: t('nav.live'), any: ['VIEW_LIVE'] },
  { to: '/playback', icon: 'history', label: t('nav.playback'), any: ['VIEW_PLAYBACK'] },
  { to: '/alerts', icon: 'bell', label: t('nav.alerts'), any: ['VIEW_LIVE', 'MANAGE_SYSTEM'] },
  { to: '/exports', icon: 'download', label: t('nav.exports'), any: ['EXPORT_VIDEO'] },
  { section: t('nav.devices') },
  { to: '/cameras', icon: 'camera', label: t('nav.cameras'), any: ['VIEW_LIVE', 'VIEW_PLAYBACK', 'CONFIGURE_CAMERA'] },
  { to: '/templates', icon: 'template', label: t('nav.templates'), any: ['CONFIGURE_CAMERA'] },
  { section: t('nav.admin') },
  { to: '/users', icon: 'users', label: t('nav.users'), any: ['MANAGE_USERS'] },
  { to: '/audit', icon: 'audit', label: t('nav.audit'), any: ['MANAGE_SYSTEM'] },
  { to: '/system', icon: 'server', label: t('nav.system'), any: ['MANAGE_SYSTEM'] },
  { to: '/poc/player', icon: 'grid', label: t('nav.pocPlayer'), any: ['MANAGE_SYSTEM'] },
])

// Bỏ mục không có quyền, rồi bỏ tiêu đề nhóm không còn mục nào
const nav = computed(() => {
  const visible = all.value.filter((i) => i.section || !i.any || i.any.some((p) => auth.can(p)))
  return visible.filter((i, idx) => !i.section || (visible[idx + 1] && !visible[idx + 1]!.section))
})

const initials = computed(() =>
  (auth.user?.displayName ?? '?')
    .split(/\s+/)
    .slice(-2)
    .map((w) => w[0]?.toUpperCase() ?? '')
    .join(''),
)

function switchLanguage(l: Locale) {
  void auth.updateLanguage(l)
}

async function logout() {
  await auth.logout()
  await router.replace('/login')
}

watch(
  () => route.fullPath,
  () => (menuOpen.value = false),
)

// Kênh thời gian thực (trạng thái camera...) mở khi đã đăng nhập
const realtime = useRealtimeStore()
watch(
  () => auth.accessToken,
  (token) => {
    const org = organizationOfToken(token)
    if (org) realtime.start(org)
    else realtime.stop()
  },
  { immediate: true },
)
onBeforeUnmount(() => realtime.stop())
</script>

<template>
  <div class="app">
    <aside
      id="sidebar"
      :class="['sidebar', { open: menuOpen }]"
    >
      <div class="brand">
        <span class="brand-mark"><AppIcon name="camera" /></span> CamHub
      </div>
      <nav class="nav">
        <template
          v-for="(item, i) in nav"
          :key="i"
        >
          <div
            v-if="item.section"
            class="nav-section"
          >
            {{ item.section }}
          </div>
          <RouterLink
            v-else-if="item.to && item.icon"
            :to="item.to"
            class="nav-item"
            active-class="active"
          >
            <AppIcon :name="item.icon" /><span>{{ item.label }}</span>
          </RouterLink>
        </template>
      </nav>
    </aside>
    <div class="main">
      <header class="topbar">
        <button
          class="icon-btn menu-toggle"
          :aria-label="t('app.openMenu')"
          @click="menuOpen = !menuOpen"
        >
          <AppIcon name="menu" />
        </button>
        <div class="title">
          <h1>{{ route.meta.titleKey ? t(route.meta.titleKey) : '' }}</h1>
          <span
            v-if="route.meta.subtitleKey"
            class="breadcrumb"
          >{{ t(route.meta.subtitleKey) }}</span>
        </div>
        <div class="spacer" />
        <div
          class="btn-group"
          role="group"
          :aria-label="t('app.language')"
        >
          <button
            :class="{ active: locale === 'vi' }"
            data-testid="lang-vi"
            @click="switchLanguage('vi')"
          >
            VI
          </button>
          <button
            :class="{ active: locale === 'en' }"
            data-testid="lang-en"
            @click="switchLanguage('en')"
          >
            EN
          </button>
        </div>
        <NotificationBell />
        <button
          class="icon-btn"
          :aria-label="t('app.toggleTheme')"
          :title="t('app.toggleTheme')"
          @click="theme.toggle()"
        >
          <AppIcon :name="theme.theme === 'dark' ? 'sun' : 'moon'" />
        </button>
        <RouterLink
          class="user-chip"
          to="/profile"
          :title="t('nav.profile')"
          data-testid="profile-link"
        >
          <span class="avatar">{{ initials }}</span>
          <span class="hide-sm small">{{ auth.user?.displayName }}</span>
        </RouterLink>
        <button
          class="icon-btn"
          :aria-label="t('app.logout')"
          :title="t('app.logout')"
          data-testid="logout"
          @click="logout"
        >
          <AppIcon name="logout" />
        </button>
      </header>
      <main class="content">
        <RouterView />
      </main>
    </div>
  </div>
</template>
