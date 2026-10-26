<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import AppIcon from '@/components/AppIcon.vue'
import { directoryApi, orgApi, type DirectoryConnection, type DirectoryMapping, type Role, type SiteNode } from '@/services/api'
import { useToastStore } from '@/stores/toast'

/**
 * LDAP/AD (US-44, design/users.html tab "LDAP/AD"): kết nối, kiểm tra kết nối, ánh xạ nhóm → vai trò + phạm vi, đồng bộ
 * ngay. Mật khẩu tài khoản dịch vụ lưu mã hóa, không hiển thị lại.
 */
const props = defineProps<{ roles: Role[] }>()
const { t } = useI18n()
const toast = useToastStore()
const conn = ref<DirectoryConnection>({
  enabled: false,
  url: 'ldaps://',
  bindDn: '',
  hasBindPassword: false,
  userBase: '',
  userFilter: '(|(uid={0})(sAMAccountName={0})(mail={0}))',
  groupBase: '',
  memberAttribute: 'member',
  displayNameAttribute: 'cn',
  mailAttribute: 'mail',
  disabledGroupDn: '',
  lastSyncAt: null,
  lastSyncStatus: null,
  lastSyncMessage: null,
})
const bindPassword = ref('')
const mappings = ref<DirectoryMapping[]>([])
const sites = ref<SiteNode[]>([])
const testResult = ref('')
const loaded = ref(false)

const zoneSites = computed(() => sites.value)

async function load() {
  try {
    const [c, m, s] = await Promise.all([directoryApi.get(), directoryApi.mappings(), orgApi.sites()])
    if (c) conn.value = { ...c, disabledGroupDn: c.disabledGroupDn ?? '' }
    mappings.value = m
    sites.value = s
    loaded.value = true
  } catch (e) {
    toast.error(e)
  }
}

async function save() {
  try {
    conn.value = await directoryApi.save({ ...conn.value, bindPassword: bindPassword.value || undefined })
    bindPassword.value = ''
    toast.ok(t('common.saved'))
  } catch (e) {
    toast.error(e)
  }
}

async function test() {
  try {
    const r = await directoryApi.test()
    testResult.value = r.ok ? t('ldap.testOk', { n: r.users ?? 0 }) : (r.message ?? t('ldap.testFailed'))
  } catch (e) {
    toast.error(e)
  }
}

async function saveMappings() {
  try {
    mappings.value = await directoryApi.saveMappings(mappings.value)
    toast.ok(t('common.saved'))
  } catch (e) {
    toast.error(e)
  }
}

async function sync() {
  try {
    const r = await directoryApi.sync()
    toast.ok(r.message)
    await load()
  } catch (e) {
    toast.error(e)
  }
}

onMounted(load)
</script>

<template>
  <section
    class="card"
    data-testid="ldap-tab"
    :data-loaded="loaded"
  >
    <div class="card-head">
      <h3>{{ t('ldap.title') }}</h3>
      <span class="spacer" />
      <label class="switch">
        <input
          v-model="conn.enabled"
          type="checkbox"
          data-testid="ldap-enabled"
        >
        <span />
      </label>
    </div>
    <div class="card-body stack">
      <div class="form-grid">
        <div class="field">
          <label for="ldap-url">URL</label>
          <input
            id="ldap-url"
            v-model="conn.url"
            class="input"
            data-testid="ldap-url"
          >
        </div>
        <div class="field">
          <label for="ldap-bind">{{ t('ldap.bindDn') }}</label>
          <input
            id="ldap-bind"
            v-model="conn.bindDn"
            class="input"
            data-testid="ldap-bind"
          >
        </div>
        <div class="field">
          <label for="ldap-pw">{{ t('ldap.bindPassword') }}</label>
          <input
            id="ldap-pw"
            v-model="bindPassword"
            class="input"
            type="password"
            autocomplete="new-password"
            :placeholder="conn.hasBindPassword ? t('ldap.passwordKept') : ''"
            data-testid="ldap-password"
          >
        </div>
        <div class="field">
          <label for="ldap-users">{{ t('ldap.userBase') }}</label>
          <input
            id="ldap-users"
            v-model="conn.userBase"
            class="input"
            data-testid="ldap-user-base"
          >
        </div>
        <div class="field">
          <label for="ldap-filter">{{ t('ldap.userFilter') }}</label>
          <input
            id="ldap-filter"
            v-model="conn.userFilter"
            class="input"
          >
        </div>
        <div class="field">
          <label for="ldap-groups">{{ t('ldap.groupBase') }}</label>
          <input
            id="ldap-groups"
            v-model="conn.groupBase"
            class="input"
            data-testid="ldap-group-base"
          >
        </div>
        <div class="field">
          <label for="ldap-disabled">{{ t('ldap.disabledGroup') }}</label>
          <input
            id="ldap-disabled"
            v-model="conn.disabledGroupDn"
            class="input"
            data-testid="ldap-disabled-group"
          >
        </div>
      </div>
      <div class="row">
        <button
          class="btn btn-primary"
          data-testid="ldap-save"
          @click="save"
        >
          {{ t('common.save') }}
        </button>
        <button
          class="btn"
          data-testid="ldap-test"
          @click="test"
        >
          {{ t('ldap.test') }}
        </button>
        <button
          class="btn"
          data-testid="ldap-sync"
          @click="sync"
        >
          <AppIcon
            name="refresh"
            small
          />{{ t('ldap.syncNow') }}
        </button>
        <span
          class="small"
          data-testid="ldap-test-result"
        >{{ testResult }}</span>
        <span
          v-if="conn.lastSyncAt"
          class="small muted"
        >{{ t('ldap.lastSync', { at: new Date(conn.lastSyncAt).toLocaleString(), msg: conn.lastSyncMessage ?? '' }) }}</span>
      </div>

      <b>{{ t('ldap.mappings') }}</b>
      <div
        v-for="(m, i) in mappings"
        :key="i"
        class="row"
        data-testid="ldap-mapping"
      >
        <input
          v-model="m.groupDn"
          class="input"
          style="flex: 2"
          :placeholder="t('ldap.groupDn')"
          :data-testid="`map-group-${i}`"
        >
        <select
          v-model="m.roleId"
          class="select"
          style="flex: 1"
          :data-testid="`map-role-${i}`"
        >
          <option
            v-for="r in props.roles"
            :key="r.id"
            :value="r.id"
          >
            {{ r.name }}
          </option>
        </select>
        <select
          :value="m.scopeType === 'ORGANIZATION' ? '' : m.scopeId ?? ''"
          class="select"
          style="flex: 1"
          :data-testid="`map-scope-${i}`"
          @change="(e) => { const v = (e.target as HTMLSelectElement).value; m.scopeType = v ? 'SITE' : 'ORGANIZATION'; m.scopeId = v || null }"
        >
          <option value="">
            {{ t('ldap.wholeOrg') }}
          </option>
          <option
            v-for="s in zoneSites"
            :key="s.id"
            :value="s.id"
          >
            {{ s.name }}
          </option>
        </select>
        <button
          class="icon-btn"
          :aria-label="t('common.delete')"
          @click="mappings.splice(i, 1)"
        >
          <AppIcon name="x" />
        </button>
      </div>
      <div class="row">
        <button
          class="btn btn-sm"
          data-testid="map-add"
          @click="mappings.push({ id: null, groupDn: '', roleId: props.roles[0]?.id ?? '', scopeType: 'ORGANIZATION', scopeId: null })"
        >
          {{ t('ldap.addMapping') }}
        </button>
        <button
          class="btn btn-sm btn-primary"
          data-testid="map-save"
          @click="saveMappings"
        >
          {{ t('ldap.saveMappings') }}
        </button>
      </div>
    </div>
  </section>
</template>
