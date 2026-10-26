<script setup lang="ts">
import { watch } from 'vue'
import { useRouter } from 'vue-router'
import ToastHost from '@/components/ToastHost.vue'
import { useThemeStore } from '@/stores/theme'
import { useAuthStore } from '@/stores/auth'

useThemeStore() // áp theme đã lưu ngay khi khởi động
const auth = useAuthStore()
const router = useRouter()

// Phiên hết hạn giữa chừng (US-30 AC3): về trang đăng nhập, giữ trang đang xem để quay lại
watch(
  () => auth.sessionExpired,
  (expired) => {
    if (expired && router.currentRoute.value.path !== '/login') {
      void router.replace({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } })
    }
  },
)
</script>

<template>
  <RouterView />
  <ToastHost />
</template>
