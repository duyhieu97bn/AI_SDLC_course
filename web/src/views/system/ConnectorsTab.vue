<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import AppIcon from '@/components/AppIcon.vue'
import AppModal from '@/components/AppModal.vue'
import { connectorApi, orgApi, type ConnectorCreated, type ConnectorStatus, type SiteConnector, type SiteNode } from '@/services/api'
import { useRealtimeStore } from '@/stores/realtime'
import { useToastStore } from '@/stores/toast'

/**
 * Bộ kết nối địa điểm (US-06, design/system.html): tạo bộ kết nối cho địa điểm sau NAT, hiển thị mã kích hoạt (1 lần,
 * 24 giờ) và lệnh cài; trạng thái, liên lạc gần nhất, băng thông cập nhật qua WebSocket.
 */
const { t } = useI18n()
const toast = useToastStore()
const realtime = useRealtimeStore()
const items = ref<SiteConnector[]>([])
const sites = ref<SiteNode[]>([])
const createOpen = ref(false)
const form = ref({ siteId: '', name: '' })
const created = ref<ConnectorCreated | null>(null)

const BADGE: Record<ConnectorStatus, string> = {
  PENDING_ACTIVATION: 'badge-muted',
  ONLINE: 'badge-ok',
  OFFLINE: 'badge-danger',
  REVOKED: 'badge-muted',
}

async function load() {
  try {
    items.value = await connectorApi.list()
  } catch (e) {
    toast.error(e)
  }
}

async function openCreate() {
  sites.value = await orgApi.sites()
  form.value = { siteId: sites.value.find((s) => !items.value.some((c) => c.siteId === s.id))?.id ?? '', name: '' }
  created.value = null
  createOpen.value = true
}

async function create() {
  try {
    created.value = await connectorApi.create(form.value.siteId, form.value.name || undefined)
    await load()
  } catch (e) {
    toast.error(e)
  }
}

async function newCode(c: SiteConnector) {
  try {
    created.value = await connectorApi.newCode(c.id)
    createOpen.value = true
    await load()
  } catch (e) {
    toast.error(e)
  }
}

async function revoke(c: SiteConnector) {
  if (!confirm(t('connectors.confirmRevoke', { name: c.name }))) return
  try {
    await connectorApi.revoke(c.id)
    await load()
  } catch (e) {
    toast.error(e)
  }
}

async function copy(text: string) {
  try {
    await navigator.clipboard.writeText(text)
    toast.ok(t('connectors.copied'))
  } catch {
    /* trình duyệt chặn clipboard: người dùng tự chọn và sao chép */
  }
}

watch(
  () => realtime.connectorUpdate,
  (u) => {
    if (!u) return
    const i = items.value.findIndex((x) => x.id === u.id)
    if (i >= 0) items.value.splice(i, 1, u)
    else items.value.push(u)
  },
)
onMounted(load)
</script>

<template>
  <section
    class="card"
    data-testid="connectors-tab"
  >
    <div class="card-head">
      <h3>{{ t('connectors.title') }}</h3>
      <span class="spacer" />
      <button
        class="btn btn-sm btn-primary"
        data-testid="connector-create"
        @click="openCreate"
      >
        <AppIcon
          name="plus"
          small
        />{{ t('connectors.create') }}
      </button>
    </div>
    <div class="table-wrap">
      <table class="table">
        <thead>
          <tr>
            <th>{{ t('connectors.name') }}</th>
            <th>{{ t('connectors.site') }}</th>
            <th>{{ t('connectors.status') }}</th>
            <th>{{ t('connectors.lastSeen') }}</th>
            <th>{{ t('connectors.bandwidth') }}</th>
            <th />
          </tr>
        </thead>
        <tbody>
          <tr v-if="items.length === 0">
            <td
              colspan="6"
              class="muted"
            >
              {{ t('connectors.empty') }}
            </td>
          </tr>
          <tr
            v-for="c in items"
            :key="c.id"
            data-testid="connector-row"
          >
            <td>
              <b>{{ c.name }}</b>
              <div
                v-if="c.hostname"
                class="small muted"
              >
                {{ c.hostname }} · {{ c.version }}
              </div>
            </td>
            <td>{{ c.siteName }}</td>
            <td>
              <span
                :class="['badge', BADGE[c.status]]"
                data-testid="connector-status"
              >{{ t(`connectors.status${c.status}`) }}</span>
            </td>
            <td class="small">
              {{ c.lastSeenAt ? new Date(c.lastSeenAt).toLocaleString() : '—' }}
            </td>
            <td class="small">
              {{ c.rxKbps != null ? `↓ ${Math.round(c.rxKbps)} / ↑ ${Math.round(c.txKbps ?? 0)} kbps` : '—' }}
            </td>
            <td class="row">
              <button
                v-if="c.status === 'PENDING_ACTIVATION'"
                class="btn btn-sm"
                @click="newCode(c)"
              >
                {{ t('connectors.newCode') }}
              </button>
              <button
                class="btn btn-sm"
                @click="revoke(c)"
              >
                {{ t('connectors.revoke') }}
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>

  <AppModal
    v-if="createOpen"
    :title="t('connectors.create')"
    size="md"
    @close="createOpen = false"
  >
    <template v-if="!created">
      <div class="field">
        <label for="cn-site">{{ t('connectors.site') }}</label>
        <select
          id="cn-site"
          v-model="form.siteId"
          class="select"
          data-testid="connector-site"
        >
          <option
            v-for="s in sites"
            :key="s.id"
            :value="s.id"
          >
            {{ s.name }}
          </option>
        </select>
      </div>
      <div class="field">
        <label for="cn-name">{{ t('connectors.name') }}</label>
        <input
          id="cn-name"
          v-model="form.name"
          class="input"
          maxlength="200"
        >
      </div>
    </template>
    <div
      v-else
      class="stack"
    >
      <p>{{ t('connectors.codeHint', { at: new Date(created.expiresAt).toLocaleString() }) }}</p>
      <div class="row">
        <code
          class="activation-code"
          data-testid="activation-code"
        >{{ created.activationCode }}</code>
        <button
          class="btn btn-sm"
          @click="copy(created.activationCode)"
        >
          <AppIcon
            name="copy"
            small
          />{{ t('connectors.copy') }}
        </button>
      </div>
      <label class="small muted">{{ t('connectors.installCommand') }}</label>
      <pre
        class="install"
        data-testid="install-command"
      >{{ created.installCommand }}</pre>
      <button
        class="btn btn-sm"
        @click="copy(created.installCommand)"
      >
        <AppIcon
          name="copy"
          small
        />{{ t('connectors.copy') }}
      </button>
    </div>
    <template #footer>
      <button
        class="btn"
        @click="createOpen = false"
      >
        {{ created ? t('common.close') : t('common.cancel') }}
      </button>
      <button
        v-if="!created"
        class="btn btn-primary"
        :disabled="!form.siteId"
        data-testid="connector-save"
        @click="create"
      >
        {{ t('connectors.createCode') }}
      </button>
    </template>
  </AppModal>
</template>

<style scoped>
.activation-code {
  font-size: 22px;
  letter-spacing: 3px;
  padding: 6px 12px;
  background: var(--surface-2);
  border-radius: 8px;
}
.install {
  white-space: pre-wrap;
  word-break: break-all;
  font-size: 12px;
  background: var(--surface-2);
  padding: 10px;
  border-radius: 8px;
  margin: 0;
}
</style>
