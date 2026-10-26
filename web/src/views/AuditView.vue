<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import AppIcon from '@/components/AppIcon.vue'
import { auditApi, type AuditEntry } from '@/services/api'
import { download } from '@/services/http'
import { useToastStore } from '@/stores/toast'

/** Nhật ký thao tác (US-33, design/audit.html): chỉ xem, lọc theo thời gian, loại, kết quả; xuất CSV. */
const { t, d } = useI18n()
const toast = useToastStore()

const ACTIONS = [
  'LOGIN', 'LOGIN_FAILED', 'VIEW_PLAYBACK', 'EXPORT_VIDEO', 'CHANGE_CONFIG', 'CHANGE_PERMISSION', 'CHANGE_ROLE',
  'MOVE_CAMERA', 'HOLD_EVIDENCE', 'RELEASE_EVIDENCE', 'TALK_AUDIO', 'REBOOT_CAMERA', 'CHANGE_CAMERA_PASSWORD',
  'EXPORT_AUDIT',
]
const SIZE = 50

const filter = reactive({ from: '', to: '', action: '', result: '', q: '' })
const page = ref(0)
const rows = ref<AuditEntry[]>([])
const total = ref(0)

function toIso(local: string) {
  return local ? new Date(local).toISOString() : null
}

function query() {
  return {
    from: toIso(filter.from),
    to: toIso(filter.to),
    action: filter.action || null,
    result: filter.result || null,
    q: filter.q || null,
  }
}

async function load() {
  try {
    const r = await auditApi.search({ ...query(), page: page.value, size: SIZE })
    rows.value = r.items
    total.value = r.total
  } catch (e) {
    toast.error(e)
  }
}

async function exportCsv() {
  try {
    await download(auditApi.exportUrl(query()), 'camhub-audit.csv')
    toast.ok(t('audit.exported'))
  } catch (e) {
    toast.error(e)
  }
}

function details(raw: string) {
  try {
    const o = JSON.parse(raw) as Record<string, unknown>
    return Object.entries(o)
      .filter(([k]) => k !== 'targetName')
      .map(([k, v]) => `${k}: ${typeof v === 'object' ? JSON.stringify(v) : String(v)}`)
      .join(' · ')
  } catch {
    return raw
  }
}

let debounce: ReturnType<typeof setTimeout> | undefined
watch(filter, () => {
  clearTimeout(debounce)
  debounce = setTimeout(() => {
    page.value = 0
    void load()
  }, 250)
})
watch(page, load)
onMounted(load)
</script>

<template>
  <div class="card">
    <div class="card-body row">
      <div class="field">
        <label for="a-from">{{ t('audit.from') }}</label>
        <input
          id="a-from"
          v-model="filter.from"
          class="input mono"
          type="datetime-local"
        >
      </div>
      <div class="field">
        <label for="a-to">{{ t('audit.to') }}</label>
        <input
          id="a-to"
          v-model="filter.to"
          class="input mono"
          type="datetime-local"
        >
      </div>
      <div class="field">
        <label for="a-action">{{ t('audit.action') }}</label>
        <select
          id="a-action"
          v-model="filter.action"
          class="input"
          data-testid="audit-action"
        >
          <option value="">
            {{ t('audit.all') }}
          </option>
          <option
            v-for="a in ACTIONS"
            :key="a"
            :value="a"
          >
            {{ a }}
          </option>
        </select>
      </div>
      <div class="field">
        <label for="a-result">{{ t('audit.result') }}</label>
        <select
          id="a-result"
          v-model="filter.result"
          class="input"
        >
          <option value="">
            {{ t('audit.all') }}
          </option>
          <option value="SUCCESS">
            {{ t('audit.success') }}
          </option>
          <option value="FAILURE">
            {{ t('audit.failure') }}
          </option>
        </select>
      </div>
      <div class="field">
        <label for="a-q">{{ t('audit.search') }}</label>
        <input
          id="a-q"
          v-model="filter.q"
          class="input"
        >
      </div>
      <span class="spacer" />
      <button
        class="btn"
        style="align-self: flex-end"
        data-testid="audit-export"
        @click="exportCsv"
      >
        <AppIcon
          name="download"
          small
        />{{ t('audit.export') }}
      </button>
    </div>
  </div>
  <div class="card">
    <div class="table-wrap">
      <table class="table">
        <thead>
          <tr>
            <th>{{ t('audit.time') }}</th>
            <th>{{ t('audit.actor') }}</th>
            <th>{{ t('audit.action') }}</th>
            <th>{{ t('audit.target') }}</th>
            <th class="hide-sm">
              IP
            </th>
            <th>{{ t('audit.result') }}</th>
            <th class="hide-sm">
              {{ t('audit.details') }}
            </th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="r in rows"
            :key="r.id"
            data-testid="audit-row"
          >
            <td class="mono">
              {{ d(new Date(r.occurredAt), 'short') }}
            </td>
            <td>{{ r.actorName ?? t('audit.system') }}</td>
            <td><span class="badge badge-muted no-dot mono">{{ r.action }}</span></td>
            <td class="wrap">
              {{ r.targetName ?? (r.targetType ? `${r.targetType}` : '—') }}
            </td>
            <td class="mono hide-sm">
              {{ r.ipAddress ?? '—' }}
            </td>
            <td>
              <span :class="['badge', r.result === 'SUCCESS' ? 'badge-ok' : 'badge-danger']">
                {{ r.result === 'SUCCESS' ? t('audit.success') : t('audit.failure') }}
              </span>
            </td>
            <td class="wrap small hide-sm">
              {{ details(r.details) }}
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
  <div class="row small muted">
    <span>{{ t('audit.showing', { from: total ? page * SIZE + 1 : 0, to: Math.min((page + 1) * SIZE, total), total }) }}</span>
    <span class="spacer" />
    <button
      class="btn btn-sm"
      :disabled="page === 0"
      @click="page--"
    >
      {{ t('audit.prev') }}
    </button>
    <button
      class="btn btn-sm"
      :disabled="(page + 1) * SIZE >= total"
      @click="page++"
    >
      {{ t('audit.next') }}
    </button>
  </div>
</template>
