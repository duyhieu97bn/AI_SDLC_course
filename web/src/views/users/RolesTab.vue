<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import AppIcon from '@/components/AppIcon.vue'
import AppModal from '@/components/AppModal.vue'
import { roleApi, type Role } from '@/services/api'
import { useAuthStore, type Permission } from '@/stores/auth'
import { useToastStore } from '@/stores/toast'

/**
 * Vai trò (US-43, design/users.html tab "Vai trò"): bảng quyền; tạo mới hoặc sao chép vai trò, sửa/xóa vai trò tùy
 * chỉnh. Vai trò hệ thống chỉ xem (INV-20); xóa vai trò đang dùng bị từ chối kèm số người (INV-21).
 */
const props = defineProps<{ roles: Role[] }>()
const emit = defineEmits<{ changed: [] }>()
const { t } = useI18n()
const auth = useAuthStore()
const toast = useToastStore()
const ALL: Permission[] = ['VIEW_LIVE', 'VIEW_PLAYBACK', 'EXPORT_VIDEO', 'CONTROL_PTZ', 'TALK_AUDIO', 'HOLD_EVIDENCE',
  'CONFIGURE_CAMERA', 'MANAGE_USERS', 'MANAGE_ROLES', 'MANAGE_SYSTEM']
const editing = ref<{ id?: string; copyFrom?: string; name: string; description: string; permissions: Permission[] } | null>(null)

function copy(r: Role) {
  editing.value = { copyFrom: r.id, name: `${r.name} (${t('roles.copySuffix')})`, description: r.description ?? '', permissions: [...r.permissions] }
}

function edit(r: Role) {
  editing.value = { id: r.id, name: r.name, description: r.description ?? '', permissions: [...r.permissions] }
}

function toggle(p: Permission) {
  if (!editing.value) return
  const list = editing.value.permissions
  const i = list.indexOf(p)
  if (i >= 0) list.splice(i, 1)
  else list.push(p)
}

async function save() {
  const e = editing.value
  if (!e) return
  try {
    if (e.id) await roleApi.update(e.id, e.name, e.description, e.permissions)
    else await roleApi.create(e.name, e.description, e.permissions, e.copyFrom)
    editing.value = null
    toast.ok(t('common.saved'))
    emit('changed')
  } catch (err) {
    toast.error(err)
  }
}

async function remove(r: Role) {
  if (!confirm(t('roles.confirmDelete', { name: r.name }))) return
  try {
    await roleApi.remove(r.id)
    emit('changed')
  } catch (err) {
    toast.error(err)
  }
}
</script>

<template>
  <section
    class="card"
    data-testid="roles-tab"
  >
    <div class="card-head">
      <h3>{{ t('users.roles') }}</h3>
      <span class="spacer" />
      <button
        v-if="auth.can('MANAGE_ROLES')"
        class="btn btn-sm btn-primary"
        data-testid="role-new"
        @click="editing = { name: '', description: '', permissions: ['VIEW_LIVE'] }"
      >
        <AppIcon
          name="plus"
          small
        />{{ t('roles.new') }}
      </button>
    </div>
    <div class="table-wrap">
      <table class="table">
        <thead>
          <tr>
            <th>{{ t('users.role') }}</th>
            <th>{{ t('users.permissions') }}</th>
            <th>{{ t('users.assignedUsers') }}</th>
            <th />
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="r in props.roles"
            :key="r.id"
            data-testid="role-row"
          >
            <td>
              <b>{{ r.name }}</b>
              <span
                v-if="r.system"
                class="badge badge-muted no-dot"
                style="margin-left: 6px"
              >{{ t('users.system') }}</span>
            </td>
            <td class="wrap small">
              {{ r.permissions.map((p) => t(`permissions.${p}`)).join(', ') }}
            </td>
            <td>{{ r.assignedUsers }}</td>
            <td class="row">
              <template v-if="auth.can('MANAGE_ROLES')">
                <button
                  class="btn btn-sm"
                  :data-testid="`role-copy-${r.code ?? r.name}`"
                  @click="copy(r)"
                >
                  <AppIcon
                    name="copy"
                    small
                  />{{ t('roles.copy') }}
                </button>
                <template v-if="!r.system">
                  <button
                    class="btn btn-sm"
                    @click="edit(r)"
                  >
                    {{ t('common.edit') }}
                  </button>
                  <button
                    class="btn btn-sm btn-ghost"
                    :data-testid="`role-delete-${r.name}`"
                    @click="remove(r)"
                  >
                    {{ t('common.delete') }}
                  </button>
                </template>
              </template>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>

  <AppModal
    v-if="editing"
    :title="editing.id ? t('common.edit') : t('roles.new')"
    @close="editing = null"
  >
    <div class="field">
      <label for="role-name">{{ t('roles.name') }}</label>
      <input
        id="role-name"
        v-model="editing.name"
        class="input"
        maxlength="100"
        data-testid="role-name"
      >
    </div>
    <div class="field">
      <label for="role-desc">{{ t('roles.description') }}</label>
      <input
        id="role-desc"
        v-model="editing.description"
        class="input"
        maxlength="500"
      >
    </div>
    <div class="perm-grid">
      <label
        v-for="p in ALL"
        :key="p"
        class="row small"
      >
        <input
          type="checkbox"
          :checked="editing.permissions.includes(p)"
          :data-testid="`perm-${p}`"
          @change="toggle(p)"
        >{{ t(`permissions.${p}`) }}
      </label>
    </div>
    <template #footer>
      <button
        class="btn"
        @click="editing = null"
      >
        {{ t('common.cancel') }}
      </button>
      <button
        class="btn btn-primary"
        :disabled="!editing.name.trim() || editing.permissions.length === 0"
        data-testid="role-save"
        @click="save"
      >
        {{ t('common.save') }}
      </button>
    </template>
  </AppModal>
</template>

<style scoped>
.perm-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 6px 12px;
}
</style>
