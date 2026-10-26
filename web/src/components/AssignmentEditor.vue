<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import AppIcon from './AppIcon.vue'
import type { Assignment, Camera, CameraGroup, Role, ScopeType, SiteNode } from '@/services/api'

/**
 * Danh sách gán vai trò theo phạm vi (US-32.4). Máy chủ là nơi kiểm tra cuối cùng INV-22/INV-27; giao diện chỉ
 * gợi ý: phạm vi Tổ chức chỉ hiện khi người sửa có quyền ở phạm vi Tổ chức.
 */
const props = defineProps<{
  modelValue: Assignment[]
  roles: Role[]
  sites: SiteNode[]
  groups: CameraGroup[]
  cameras: Camera[]
  allowOrganization: boolean
}>()
const emit = defineEmits<{ 'update:modelValue': [Assignment[]] }>()
const { t } = useI18n()

const scopeTypes = computed<ScopeType[]>(() =>
  (['ORGANIZATION', 'SITE', 'GROUP', 'CAMERA'] as ScopeType[]).filter((s) => s !== 'ORGANIZATION' || props.allowOrganization),
)

function update(i: number, patch: Partial<Assignment>) {
  const next = props.modelValue.map((a, idx) => (idx === i ? { ...a, ...patch } : a))
  emit('update:modelValue', next)
}
function setScopeType(i: number, scopeType: ScopeType) {
  update(i, { scopeType, scopeId: null })
}
function add() {
  const role = props.roles[props.roles.length - 1]
  emit('update:modelValue', [
    ...props.modelValue,
    { roleId: role?.id ?? '', scopeType: props.allowOrganization ? 'ORGANIZATION' : 'SITE', scopeId: null, source: 'MANUAL' },
  ])
}
function remove(i: number) {
  emit('update:modelValue', props.modelValue.filter((_, idx) => idx !== i))
}
function options(type: ScopeType): { id: string; name: string }[] {
  if (type === 'SITE') return props.sites.map((s) => ({ id: s.id, name: s.name }))
  if (type === 'GROUP') return props.groups.map((g) => ({ id: g.id, name: g.name }))
  if (type === 'CAMERA') return props.cameras.map((c) => ({ id: c.id, name: c.name }))
  return []
}
</script>

<template>
  <div class="stack">
    <div
      v-for="(a, i) in modelValue"
      :key="i"
      class="row assignment-row"
      data-testid="assignment-row"
    >
      <select
        class="input"
        :value="a.roleId"
        :disabled="a.source === 'DIRECTORY'"
        :aria-label="t('users.role')"
        style="flex: 1 1 160px"
        @change="update(i, { roleId: ($event.target as HTMLSelectElement).value })"
      >
        <option
          v-for="r in roles"
          :key="r.id"
          :value="r.id"
        >
          {{ r.name }}
        </option>
      </select>
      <select
        class="input"
        :value="a.scopeType"
        :disabled="a.source === 'DIRECTORY'"
        :aria-label="t('users.scopeType')"
        style="flex: 0 1 140px"
        @change="setScopeType(i, ($event.target as HTMLSelectElement).value as ScopeType)"
      >
        <option
          v-for="s in scopeTypes"
          :key="s"
          :value="s"
        >
          {{ t(`users.scope${s}`) }}
        </option>
      </select>
      <select
        v-if="a.scopeType !== 'ORGANIZATION'"
        class="input"
        :value="a.scopeId ?? ''"
        :disabled="a.source === 'DIRECTORY'"
        :aria-label="t('users.scopeTarget')"
        style="flex: 1 1 180px"
        @change="update(i, { scopeId: ($event.target as HTMLSelectElement).value || null })"
      >
        <option
          value=""
          disabled
        >
          {{ t('common.choose') }}
        </option>
        <option
          v-for="o in options(a.scopeType)"
          :key="o.id"
          :value="o.id"
        >
          {{ o.name }}
        </option>
      </select>
      <span
        v-if="a.source === 'DIRECTORY'"
        class="badge badge-info no-dot"
      >LDAP</span>
      <button
        v-else
        class="icon-btn"
        :aria-label="t('common.delete')"
        @click="remove(i)"
      >
        <AppIcon name="trash" />
      </button>
    </div>
    <button
      class="btn btn-sm"
      style="align-self: flex-start"
      data-testid="add-assignment"
      @click="add"
    >
      <AppIcon
        name="plus"
        small
      />{{ t('users.addAssignment') }}
    </button>
  </div>
</template>
