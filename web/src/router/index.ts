import { createRouter, createWebHistory } from 'vue-router'
import AppLayout from '@/layouts/AppLayout.vue'
import { useAuthStore, type Permission } from '@/stores/auth'

declare module 'vue-router' {
  interface RouteMeta {
    titleKey?: string
    subtitleKey?: string
    /** Trang công khai, không cần đăng nhập */
    public?: boolean
    /** Cần ít nhất một trong các quyền (ở bất kỳ phạm vi nào) */
    any?: Permission[]
  }
}

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: () => import('@/views/LoginView.vue'), meta: { public: true } },
    {
      path: '/',
      component: AppLayout,
      children: [
        {
          path: '',
          // Chưa đăng nhập thì landingPage là '/': chuyển tiếp về chính nó sẽ lặp vô hạn (redirect chạy trước beforeEach)
          redirect: () => {
            const landing = useAuthStore().landingPage
            return landing && landing !== '/' ? landing : '/cameras'
          },
        },
        {
          path: 'dashboard',
          component: () => import('@/views/DashboardView.vue'),
          meta: { titleKey: 'dashboard.title', any: ['MANAGE_SYSTEM', 'MANAGE_USERS', 'CONFIGURE_CAMERA'] },
        },
        {
          path: 'live',
          component: () => import('@/views/LiveView.vue'),
          meta: { titleKey: 'live.title', any: ['VIEW_LIVE'] },
        },
        {
          path: 'cameras',
          component: () => import('@/views/CamerasView.vue'),
          meta: { titleKey: 'cameras.title', subtitleKey: 'cameras.subtitle', any: ['VIEW_LIVE', 'VIEW_PLAYBACK', 'CONFIGURE_CAMERA'] },
        },
        {
          path: 'cameras/:id',
          component: () => import('@/views/CameraDetailView.vue'),
          meta: { titleKey: 'cameraDetail.title', any: ['VIEW_LIVE', 'VIEW_PLAYBACK', 'CONFIGURE_CAMERA'] },
        },
        {
          path: 'playback',
          component: () => import('@/views/PlaybackView.vue'),
          meta: { titleKey: 'playback.title', any: ['VIEW_PLAYBACK'] },
        },
        {
          path: 'alerts',
          component: () => import('@/views/AlertsView.vue'),
          meta: { titleKey: 'alerts.title', any: ['VIEW_LIVE', 'MANAGE_SYSTEM'] },
        },
        {
          path: 'exports',
          component: () => import('@/views/ExportsView.vue'),
          meta: { titleKey: 'exports.title', any: ['EXPORT_VIDEO'] },
        },
        {
          path: 'templates',
          component: () => import('@/views/TemplatesView.vue'),
          meta: { titleKey: 'templates.title', any: ['CONFIGURE_CAMERA'] },
        },
        {
          path: 'users',
          component: () => import('@/views/UsersView.vue'),
          meta: { titleKey: 'users.title', subtitleKey: 'users.subtitle', any: ['MANAGE_USERS'] },
        },
        {
          path: 'audit',
          component: () => import('@/views/AuditView.vue'),
          meta: { titleKey: 'audit.title', subtitleKey: 'audit.subtitle', any: ['MANAGE_SYSTEM'] },
        },
        {
          path: 'system',
          component: () => import('@/views/SystemView.vue'),
          meta: { titleKey: 'system.title', any: ['MANAGE_SYSTEM'] },
        },
        { path: 'profile', component: () => import('@/views/ProfileView.vue'), meta: { titleKey: 'profile.title' } },
        {
          path: 'poc/player',
          component: () => import('@/views/PocPlayerView.vue'),
          meta: { titleKey: 'poc.title', subtitleKey: 'poc.subtitle', any: ['MANAGE_SYSTEM'] },
        },
        { path: 'forbidden', component: () => import('@/views/ForbiddenView.vue'), meta: { titleKey: 'errors.FORBIDDEN' } },
      ],
    },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  await auth.init()
  if (to.meta.public) {
    return auth.isAuthenticated && to.path === '/login' ? auth.landingPage : true
  }
  if (!auth.isAuthenticated) {
    return { path: '/login', query: to.fullPath !== '/' ? { redirect: to.fullPath } : {} }
  }
  if (to.meta.any && !to.meta.any.some((p) => auth.can(p))) {
    return '/forbidden'
  }
  return true
})
