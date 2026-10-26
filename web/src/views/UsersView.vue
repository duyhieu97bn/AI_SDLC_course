<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import AppIcon from '@/components/AppIcon.vue'
import UserDialog from '@/components/UserDialog.vue'
import LdapTab from '@/views/users/LdapTab.vue'
import RolesTab from '@/views/users/RolesTab.vue'
import {
  cameraApi,
  orgApi,
  userApi,
  type Camera,
  type CameraGroup,
  type Role,
  type SiteNode,
  type User,
} from '@/services/api'
import { http } from '@/services/http'
import { useToastStore } from '@/stores/toast'

/** Người dùng và vai trò (US-32, design/users.html). Site Manager chỉ thấy người trong địa điểm của mình. */
const { t, d } = useI18n()
const toast = useToastStore()

const users = ref<User[]>([])
const roles = ref<Role[]>([])
const sites = ref<SiteNode[]>([])
const groups = ref<CameraGroup[]>([])
const cameras = ref<Camera[]>([])
const q = ref('')
const editing = ref<User | null | undefined>(undefined) // undefined = đóng, null = tạo mới
const orgWide = ref(false)

async function reloadRoles() {
  roles.value = await userApi.roles().catch(() => roles.value)
}

async function load() {
  try {
    users.value = await userApi.list(q.value || undefined)
  } catch (e) {
    toast.error(e)
  }
}

onMounted(async () => {
  try {
    const [r, s, g, c] = await Promise.all([userApi.roles(), orgApi.sites(), orgApi.groups(), cameraApi.list({ size: 1000 })])
    roles.value = r
    sites.value = s
    groups.value = g
    cameras.value = c.items
    // Có thấy toàn bộ người dùng (không lọc) khi có MANAGE_USERS phạm vi Tổ chức; giao diện dùng để hiện lựa chọn "Toàn Tổ chức"
    const scope = await http.get<{ all: boolean }>('/me/manage-scope').catch(() => ({ all: false }))
    orgWide.value = scope.all
  } catch (e) {
    toast.error(e)
  }
  await load()
})

let debounce: ReturnType<typeof setTimeout> | undefined
watch(q, () => {
  clearTimeout(debounce)
  debounce = setTimeout(() => void load(), 250)
})

function statusBadge(s: User['status']) {
  return s === 'ACTIVE' ? 'badge-ok' : s === 'LOCKED' ? 'badge-warn' : 'badge-muted'
}

async function onSaved() {
  editing.value = undefined
  toast.ok(t('common.saved'))
  await load()
}
</script>

<template>
  <div class="row">
    <label
      class="search"
      style="display: flex"
    >
      <AppIcon name="search" />
      <input
        v-model="q"
        :placeholder="t('users.search')"
        :aria-label="t('users.search')"
      >
    </label>
    <span class="spacer" />
    <button
      class="btn btn-primary"
      data-testid="create-user"
      @click="editing = null"
    >
      <AppIcon
        name="userPlus"
        small
      />{{ t('users.create') }}
    </button>
  </div>

  <div class="card">
    <div class="table-wrap">
      <table class="table">
        <thead>
          <tr>
            <th>{{ t('users.displayName') }}</th>
            <th>Email</th>
            <th>{{ t('users.status') }}</th>
            <th class="hide-sm">
              {{ t('users.source') }}
            </th>
            <th class="hide-sm">
              {{ t('users.lastLogin') }}
            </th>
            <th />
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="u in users"
            :key="u.id"
            data-testid="user-row"
          >
            <td class="wrap">
              <b>{{ u.displayName }}</b>
            </td>
            <td class="wrap">
              {{ u.email }}
            </td>
            <td>
              <span :class="['badge', statusBadge(u.status)]">{{ t(`users.status${u.status}`) }}</span>
            </td>
            <td class="hide-sm">
              {{ u.authSource === 'LOCAL' ? t('users.local') : 'LDAP/AD' }}
            </td>
            <td class="hide-sm">
              {{ u.lastLoginAt ? d(new Date(u.lastLoginAt), 'short') : '—' }}
            </td>
            <td>
              <button
                class="btn btn-sm"
                @click="editing = u"
              >
                <AppIcon
                  name="edit"
                  small
                />{{ t('common.edit') }}
              </button>
            </td>
          </tr>
          <tr v-if="users.length === 0">
            <td
              colspan="6"
              class="muted"
              style="text-align: center; padding: 24px"
            >
              {{ t('users.empty') }}
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>

  <RolesTab
    :roles="roles"
    @changed="reloadRoles"
  />
  <LdapTab
    v-if="orgWide"
    :roles="roles"
  />

  <UserDialog
    v-if="editing !== undefined"
    :user="editing"
    :roles="roles"
    :sites="sites"
    :groups="groups"
    :cameras="cameras"
    :allow-organization="orgWide"
    @close="editing = undefined"
    @saved="onSaved"
  />
</template>
