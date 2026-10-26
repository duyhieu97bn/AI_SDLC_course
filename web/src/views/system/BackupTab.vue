<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { systemApi, type BackupStatus } from '@/services/api'
import { useToastStore } from '@/stores/toast'

/**
 * Sao lưu – chỉ xem (US-40 AC3, design/system.html): Admin thấy trạng thái, không có nút khôi phục; khôi phục do đội
 * vận hành IT làm theo docs/runbook/restore.md (UCQ-03).
 */
const { t } = useI18n()
const toast = useToastStore()
const status = ref<BackupStatus | null>(null)

const time = (s: string | null | undefined) => (s ? new Date(s).toLocaleString() : '—')
const mb = (b: number | null | undefined) => (b == null ? '—' : `${(b / 1_048_576).toFixed(1)} MB`)

onMounted(async () => {
  try {
    status.value = await systemApi.backups()
  } catch (e) {
    toast.error(e)
  }
})
</script>

<template>
  <section
    class="card"
    data-testid="backup-tab"
  >
    <div class="card-head">
      <h3>{{ t('backup.title') }}</h3>
    </div>
    <div
      v-if="status"
      class="card-body stack"
    >
      <p
        v-if="!status.available"
        class="muted"
      >
        {{ t('backup.unavailable') }}
      </p>
      <template v-else>
        <div class="row">
          <b>{{ t('backup.database') }}</b>
          <span
            :class="['badge', status.postgres?.status === 'OK' ? 'badge-ok' : 'badge-danger']"
            data-testid="backup-pg-status"
          >{{ status.postgres?.status ?? '—' }}</span>
          <span class="small muted">{{ status.postgres?.message }}</span>
        </div>
        <div class="small">
          {{ t('backup.last', { at: time(status.postgres?.lastBackupAt), type: status.postgres?.lastBackupType ?? '—' }) }} ·
          {{ t('backup.count', { n: status.postgres?.backupCount ?? 0, full: status.postgres?.fullCount ?? 0 }) }} ·
          {{ t('backup.wal', { at: time(status.postgres?.lastWalArchivedAt) }) }}
        </div>
        <div class="row">
          <b>{{ t('backup.objects') }}</b>
          <span :class="['badge', status.minio?.status === 'OK' ? 'badge-ok' : status.minio?.status === 'ERROR' ? 'badge-danger' : 'badge-muted']">
            {{ status.minio?.status ?? '—' }}
          </span>
          <span class="small muted">{{ t('backup.mirror', { at: time(status.minio?.lastMirrorAt) }) }}</span>
        </div>
        <div class="table-wrap">
          <table class="table">
            <thead>
              <tr>
                <th>{{ t('backup.label') }}</th>
                <th>{{ t('backup.type') }}</th>
                <th>{{ t('backup.finished') }}</th>
                <th>{{ t('backup.size') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="b in (status.postgres?.backups ?? []).slice(0, 10)"
                :key="b.label"
                data-testid="backup-row"
              >
                <td class="small">
                  {{ b.label }}
                </td>
                <td>{{ b.type }}</td>
                <td class="small">
                  {{ time(b.finishedAt) }}
                </td>
                <td class="small">
                  {{ mb(b.sizeBytes) }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>
      <p class="small muted">
        {{ t('backup.restoreHint') }}
      </p>
    </div>
  </section>
</template>
