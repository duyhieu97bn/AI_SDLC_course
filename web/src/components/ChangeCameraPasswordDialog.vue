<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import AppModal from '@/components/AppModal.vue'
import { maintenanceApi, type Camera, type PasswordResult } from '@/services/api'
import { useToastStore } from '@/stores/toast'

/**
 * Đổi mật khẩu camera hàng loạt (US-28 AC1): camera đổi thành công được cập nhật mật khẩu đã lưu; camera thất bại giữ
 * mật khẩu cũ và hiện lỗi từng camera.
 */
const props = defineProps<{ cameras: Camera[] }>()
const emit = defineEmits<{ close: [] }>()
const { t } = useI18n()
const toast = useToastStore()
const password = ref('')
const confirmPw = ref('')
const busy = ref(false)
const results = ref<PasswordResult[] | null>(null)
const problem = computed(() => {
  if (password.value.length < 8) return t('maintenance.minLength')
  if (password.value !== confirmPw.value) return t('maintenance.mismatch')
  return ''
})

async function submit() {
  busy.value = true
  try {
    results.value = await maintenanceApi.changePasswords(props.cameras.map((c) => c.id), password.value)
  } catch (e) {
    toast.error(e)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <AppModal
    :title="t('maintenance.changePassword')"
    size="sm"
    @close="emit('close')"
  >
    <template v-if="!results">
      <p class="small muted">
        {{ t('maintenance.forCameras', { n: cameras.length }) }}
      </p>
      <div class="field">
        <label for="pw-new">{{ t('maintenance.newPassword') }}</label>
        <input
          id="pw-new"
          v-model="password"
          class="input"
          type="password"
          autocomplete="new-password"
          data-testid="cam-pw"
        >
      </div>
      <div class="field">
        <label for="pw-confirm">{{ t('maintenance.confirmPassword') }}</label>
        <input
          id="pw-confirm"
          v-model="confirmPw"
          class="input"
          type="password"
          autocomplete="new-password"
          data-testid="cam-pw2"
        >
      </div>
      <p
        v-if="password && problem"
        class="small"
        style="color: var(--danger)"
      >
        {{ problem }}
      </p>
    </template>
    <ul
      v-else
      class="stack"
      data-testid="pw-results"
    >
      <li
        v-for="r in results"
        :key="r.cameraId"
        class="row"
      >
        <span :class="['badge', r.ok ? 'badge-ok' : 'badge-danger']">{{ r.ok ? t('maintenance.changed') : t('maintenance.failed') }}</span>
        <b>{{ r.name }}</b>
        <span
          v-if="!r.ok"
          class="small muted"
        >{{ r.message }}</span>
      </li>
    </ul>
    <template #footer>
      <button
        class="btn"
        @click="emit('close')"
      >
        {{ results ? t('common.close') : t('common.cancel') }}
      </button>
      <button
        v-if="!results"
        class="btn btn-primary"
        :disabled="!!problem || busy"
        data-testid="cam-pw-save"
        @click="submit"
      >
        {{ t('maintenance.apply') }}
      </button>
    </template>
  </AppModal>
</template>
