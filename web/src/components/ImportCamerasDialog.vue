<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import AppModal from '@/components/AppModal.vue'
import { importApi, type ImportResult, type ImportRow } from '@/services/api'
import { useToastStore } from '@/stores/toast'

/**
 * Nhập camera từ tệp .csv/.xlsx (US-07): xem trước từng dòng kèm lỗi; bấm Nhập thì thêm các dòng hợp lệ, dòng lỗi
 * bị bỏ qua và có báo cáo kết quả.
 */
const emit = defineEmits<{ close: []; done: [] }>()
const { t } = useI18n()
const toast = useToastStore()
const file = ref<File | null>(null)
const rows = ref<ImportRow[] | null>(null)
const results = ref<ImportResult[] | null>(null)
const saveUnverified = ref(false)
const busy = ref(false)
const valid = computed(() => rows.value?.filter((r) => r.errors.length === 0).length ?? 0)

const TEMPLATE = 'Tên,Địa điểm,Khu vực,Giao thức,Địa chỉ IP,Cổng,Tài khoản,Mật khẩu,URL RTSP\n'
  + 'Cổng kho 1,Kho Bắc Ninh,Cổng chính,ONVIF,192.168.1.64,80,admin,matkhau,\n'
  + 'Bãi xe 9,Kho Bắc Ninh,Bãi xe,RTSP,,,admin,matkhau,rtsp://192.168.1.65:554/Streaming/Channels/101\n'

function downloadTemplate() {
  const a = document.createElement('a')
  a.href = URL.createObjectURL(new Blob(['﻿' + TEMPLATE], { type: 'text/csv' }))
  a.download = 'camhub-mau-nhap-camera.csv'
  a.click()
  URL.revokeObjectURL(a.href)
}

async function pick(e: Event) {
  const f = (e.target as HTMLInputElement).files?.[0] ?? null
  file.value = f
  rows.value = null
  results.value = null
  if (!f) return
  busy.value = true
  try {
    rows.value = (await importApi.preview(f)).rows
  } catch (err) {
    toast.error(err)
  } finally {
    busy.value = false
  }
}

async function run() {
  if (!file.value) return
  busy.value = true
  try {
    results.value = await importApi.run(file.value, saveUnverified.value)
    const ok = results.value.filter((r) => r.ok).length
    toast.ok(t('import.done', { ok, skipped: results.value.length - ok }))
    emit('done')
  } catch (err) {
    toast.error(err)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <AppModal
    :title="t('import.title')"
    @close="emit('close')"
  >
    <div class="row">
      <input
        type="file"
        accept=".csv,.xlsx"
        data-testid="import-file"
        @change="pick"
      >
      <a
        href="#"
        class="small"
        @click.prevent="downloadTemplate"
      >{{ t('import.template') }}</a>
    </div>
    <p class="small muted">
      {{ t('import.hint') }}
    </p>
    <div
      v-if="rows && !results"
      class="table-wrap"
      style="max-height: 320px; overflow-y: auto"
    >
      <p class="small">
        {{ t('import.summary', { valid, total: rows.length }) }}
      </p>
      <table class="table">
        <tbody>
          <tr
            v-for="r in rows"
            :key="r.line"
            data-testid="import-row"
            :class="{ muted: r.errors.length > 0 }"
          >
            <td>#{{ r.line }}</td>
            <td>{{ r.name }}</td>
            <td class="small">
              {{ r.site }} / {{ r.zone }}
            </td>
            <td class="small">
              {{ r.protocol }} {{ r.host ?? r.rtspUrl }}
            </td>
            <td>
              <span
                v-if="r.errors.length === 0"
                class="badge badge-ok"
              >OK</span>
              <span
                v-else
                class="small"
                style="color: var(--danger)"
                data-testid="import-error"
              >{{ r.errors.join('; ') }}</span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <ul
      v-if="results"
      class="stack small"
      data-testid="import-results"
    >
      <li
        v-for="r in results"
        :key="r.line"
      >
        <span :class="['badge', r.ok ? 'badge-ok' : 'badge-danger']">{{ r.ok ? t('import.added') : t('import.skipped') }}</span>
        #{{ r.line }} {{ r.name }}
        <span
          v-if="!r.ok"
          class="muted"
        > – {{ r.message }}</span>
      </li>
    </ul>
    <label
      v-if="rows && !results"
      class="row small"
    >
      <input
        v-model="saveUnverified"
        type="checkbox"
      >{{ t('addCamera.saveUnverified') }}
    </label>
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
        :disabled="busy || valid === 0"
        data-testid="import-run"
        @click="run"
      >
        {{ t('import.run', { n: valid }) }}
      </button>
    </template>
  </AppModal>
</template>
