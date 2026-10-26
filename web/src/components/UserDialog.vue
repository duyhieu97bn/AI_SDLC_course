<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import AppModal from './AppModal.vue'
import AssignmentEditor from './AssignmentEditor.vue'
import {
  userApi,
  type Assignment,
  type Camera,
  type CameraGroup,
  type Role,
  type SiteNode,
  type User,
  type UserStatus,
} from '@/services/api'
import { ApiError } from '@/services/http'
import { errorText } from '@/i18n'

/** Tạo người dùng hoặc sửa thông tin, trạng thái và gán vai trò (US-32, design/users.html). */
const props = defineProps<{
  user: User | null
  roles: Role[]
  sites: SiteNode[]
  groups: CameraGroup[]
  cameras: Camera[]
  allowOrganization: boolean
}>()
const emit = defineEmits<{ close: []; saved: [] }>()
const { t } = useI18n()

const email = ref(props.user?.email ?? '')
const displayName = ref(props.user?.displayName ?? '')
const password = ref('')
const language = ref<'vi' | 'en'>(props.user?.language ?? 'vi')
const status = ref<UserStatus>(props.user?.status ?? 'ACTIVE')
const assignments = ref<Assignment[]>([])
const error = ref('')
const busy = ref(false)
const isNew = computed(() => props.user === null)

onMounted(async () => {
  if (props.user) {
    try {
      assignments.value = await userApi.assignments(props.user.id)
    } catch (e) {
      error.value = e instanceof ApiError ? errorText(e.code, e.message) : String(e)
    }
  }
})

async function save() {
  error.value = ''
  if (assignments.value.some((a) => a.scopeType !== 'ORGANIZATION' && !a.scopeId)) {
    error.value = t('users.scopeMissing')
    return
  }
  busy.value = true
  try {
    if (isNew.value) {
      await userApi.create({
        email: email.value,
        displayName: displayName.value,
        password: password.value,
        language: language.value,
        assignments: assignments.value,
      })
    } else if (props.user) {
      await userApi.update(props.user.id, { displayName: displayName.value, language: language.value, status: status.value })
      await userApi.setAssignments(
        props.user.id,
        assignments.value.filter((a) => a.source !== 'DIRECTORY'),
      )
      if (password.value) await userApi.resetPassword(props.user.id, password.value)
    }
    emit('saved')
  } catch (e) {
    error.value = e instanceof ApiError ? errorText(e.code, e.message) : String(e)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <AppModal
    :title="isNew ? t('users.create') : t('users.edit')"
    @close="emit('close')"
  >
    <div
      v-if="error"
      class="alert-box danger"
      role="alert"
      data-testid="user-error"
    >
      {{ error }}
    </div>
    <div class="form-grid">
      <div class="field">
        <label for="u-email">Email</label>
        <input
          id="u-email"
          v-model="email"
          class="input"
          type="email"
          :disabled="!isNew"
          autocomplete="off"
        >
      </div>
      <div class="field">
        <label for="u-name">{{ t('users.displayName') }}</label>
        <input
          id="u-name"
          v-model="displayName"
          class="input"
          maxlength="200"
        >
      </div>
      <div
        v-if="!user || user.authSource === 'LOCAL'"
        class="field"
      >
        <label for="u-pass">{{ isNew ? t('users.password') : t('users.newPassword') }}</label>
        <input
          id="u-pass"
          v-model="password"
          class="input"
          type="password"
          autocomplete="new-password"
        >
        <span class="hint">{{ t('users.passwordHint') }}</span>
      </div>
      <div class="field">
        <label for="u-lang">{{ t('users.language') }}</label>
        <select
          id="u-lang"
          v-model="language"
          class="input"
        >
          <option value="vi">
            Tiếng Việt
          </option>
          <option value="en">
            English
          </option>
        </select>
      </div>
      <div
        v-if="!isNew"
        class="field"
      >
        <label for="u-status">{{ t('users.status') }}</label>
        <select
          id="u-status"
          v-model="status"
          class="input"
        >
          <option value="ACTIVE">
            {{ t('users.statusACTIVE') }}
          </option>
          <option value="LOCKED">
            {{ t('users.statusLOCKED') }}
          </option>
          <option value="DISABLED">
            {{ t('users.statusDISABLED') }}
          </option>
        </select>
      </div>
    </div>
    <div>
      <h3 style="margin-bottom: 8px">
        {{ t('users.assignments') }}
      </h3>
      <AssignmentEditor
        v-model="assignments"
        :roles="roles"
        :sites="sites"
        :groups="groups"
        :cameras="cameras"
        :allow-organization="allowOrganization"
      />
    </div>
    <template #footer>
      <button
        class="btn"
        @click="emit('close')"
      >
        {{ t('common.cancel') }}
      </button>
      <button
        class="btn btn-primary"
        :disabled="busy"
        data-testid="user-save"
        @click="save"
      >
        {{ t('common.save') }}
      </button>
    </template>
  </AppModal>
</template>
