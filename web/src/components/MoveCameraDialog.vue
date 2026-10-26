<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import AppModal from './AppModal.vue'
import { orgApi, type Camera, type SiteNode } from '@/services/api'
import { ApiError } from '@/services/http'
import { errorText } from '@/i18n'

/**
 * Chuyển camera sang khu vực khác (US-05 AC3). Chạy từng camera và báo kết quả riêng từng cái
 * (SADD 3.7.1: thao tác hàng loạt không dừng khi một phần tử lỗi).
 */
const props = defineProps<{ cameras: Camera[]; sites: SiteNode[]; initialZoneId?: string }>()
const emit = defineEmits<{ close: []; done: [] }>()
const { t } = useI18n()

const zoneId = ref(props.initialZoneId ?? '')
const busy = ref(false)
const results = ref<Record<string, string>>({})
const manageable = computed(() => props.sites.filter((s) => s.manageable && s.zones.length > 0))

async function submit() {
  busy.value = true
  results.value = {}
  for (const c of props.cameras) {
    try {
      await orgApi.moveCamera(c.id, zoneId.value)
      results.value[c.id] = 'ok'
    } catch (e) {
      results.value[c.id] = e instanceof ApiError ? errorText(e.code, e.message) : String(e)
    }
  }
  busy.value = false
  if (Object.values(results.value).every((r) => r === 'ok')) {
    emit('done')
  }
}
</script>

<template>
  <AppModal
    :title="t('cameras.moveTitle', { n: cameras.length })"
    size="sm"
    @close="emit('close')"
  >
    <div class="alert-box info">
      {{ t('cameras.moveNote') }}
    </div>
    <div class="field">
      <label for="target-zone">{{ t('cameras.targetZone') }}</label>
      <select
        id="target-zone"
        v-model="zoneId"
        class="input"
      >
        <option
          value=""
          disabled
        >
          {{ t('common.choose') }}
        </option>
        <optgroup
          v-for="s in manageable"
          :key="s.id"
          :label="s.name"
        >
          <option
            v-for="z in s.zones"
            :key="z.id"
            :value="z.id"
          >
            {{ z.name }}
          </option>
        </optgroup>
      </select>
    </div>
    <ul
      v-if="Object.keys(results).length"
      class="small"
    >
      <li
        v-for="c in cameras"
        :key="c.id"
      >
        {{ c.name }}:
        <span :style="{ color: results[c.id] === 'ok' ? 'var(--success)' : 'var(--danger)' }">
          {{ results[c.id] === 'ok' ? t('common.done') : results[c.id] }}
        </span>
      </li>
    </ul>
    <template #footer>
      <button
        class="btn"
        @click="emit('close')"
      >
        {{ t('common.cancel') }}
      </button>
      <button
        class="btn btn-primary"
        :disabled="!zoneId || busy"
        data-testid="move-submit"
        @click="submit"
      >
        {{ t('cameras.move') }}
      </button>
    </template>
  </AppModal>
</template>
