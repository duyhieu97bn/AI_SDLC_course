<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '@/stores/auth'

const { t } = useI18n()
const auth = useAuthStore()
</script>

<template>
  <div class="alert-box warn">
    {{ t('errors.FORBIDDEN') }}
  </div>
  <RouterLink
    class="btn"
    :to="auth.landingPage"
  >
    {{ t('common.home') }}
  </RouterLink>
</template>
