<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import AppModal from '@/components/AppModal.vue'
import { recorderApi, type RecorderInfo, type SiteNode } from '@/services/api'
import { useToastStore } from '@/stores/toast'

/**
 * Thêm đầu ghi NVR/DVR (US-03, design/cameras.html "Thêm đầu ghi"): đọc danh sách kênh, chọn kênh có tín hiệu; mỗi
 * kênh thành một camera, tên lấy từ đầu ghi (sửa được).
 */
const props = defineProps<{ sites: SiteNode[]; initialZoneId?: string }>()
const emit = defineEmits<{ close: []; saved: [] }>()
const { t } = useI18n()
const toast = useToastStore()

const form = reactive({ zoneId: props.initialZoneId ?? '', name: '', host: '', port: 80, username: 'admin', password: '' })
const info = ref<RecorderInfo | null>(null)
const chosen = ref<Record<number, { on: boolean; name: string }>>({})
const busy = ref(false)
const zones = computed(() => props.sites.filter((s) => s.manageable && s.zones.length))
const siteId = computed(() => props.sites.find((s) => s.zones.some((z) => z.id === form.zoneId))?.id ?? '')
const count = computed(() => Object.values(chosen.value).filter((c) => c.on).length)

function connection() {
  return { host: form.host.trim(), port: form.port, username: form.username, password: form.password }
}

async function readChannels() {
  busy.value = true
  info.value = null
  try {
    info.value = await recorderApi.channels(siteId.value, connection())
    chosen.value = Object.fromEntries(info.value.channels.map((c) => [c.channel, { on: c.hasSignal, name: c.name }]))
  } catch (e) {
    toast.error(e)
  } finally {
    busy.value = false
  }
}

async function save() {
  busy.value = true
  try {
    const channels = Object.entries(chosen.value)
      .filter(([, v]) => v.on)
      .map(([k, v]) => ({ channel: Number(k), name: v.name }))
    const r = await recorderApi.create(form.zoneId, form.name, connection(), channels)
    toast.ok(t('recorder.saved', { n: r.cameraIds.length }))
    emit('saved')
    emit('close')
  } catch (e) {
    toast.error(e)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <AppModal
    :title="t('recorder.title')"
    @close="emit('close')"
  >
    <div class="form-grid">
      <div class="field">
        <label for="rec-zone">{{ t('addCamera.zone') }}</label>
        <select
          id="rec-zone"
          v-model="form.zoneId"
          class="select"
          data-testid="rec-zone"
        >
          <optgroup
            v-for="s in zones"
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
      <div class="field">
        <label for="rec-name">{{ t('recorder.name') }}</label>
        <input
          id="rec-name"
          v-model="form.name"
          class="input"
          maxlength="200"
        >
      </div>
      <div class="field">
        <label for="rec-host">{{ t('addCamera.host') }}</label>
        <input
          id="rec-host"
          v-model="form.host"
          class="input"
          data-testid="rec-host"
          placeholder="192.168.1.100"
        >
      </div>
      <div class="field">
        <label for="rec-port">{{ t('addCamera.port') }}</label>
        <input
          id="rec-port"
          v-model.number="form.port"
          class="input"
          type="number"
          data-testid="rec-port"
        >
      </div>
      <div class="field">
        <label for="rec-user">{{ t('addCamera.username') }}</label>
        <input
          id="rec-user"
          v-model="form.username"
          class="input"
          autocomplete="off"
          data-testid="rec-user"
        >
      </div>
      <div class="field">
        <label for="rec-pass">{{ t('addCamera.password') }}</label>
        <input
          id="rec-pass"
          v-model="form.password"
          class="input"
          type="password"
          autocomplete="new-password"
          data-testid="rec-pass"
        >
      </div>
    </div>
    <button
      class="btn"
      :disabled="busy || !form.host || !siteId"
      data-testid="rec-read"
      @click="readChannels"
    >
      {{ t('recorder.readChannels') }}
    </button>
    <div
      v-if="info"
      class="stack"
      style="margin-top: 12px"
    >
      <p class="small muted">
        {{ t('recorder.summary', { m: `${info.manufacturer ?? ''} ${info.model ?? ''}`, n: info.channelCount, s: info.channels.filter((c) => c.hasSignal).length }) }}
      </p>
      <div
        class="table-wrap"
        style="max-height: 320px; overflow-y: auto"
      >
        <table class="table">
          <tbody>
            <tr
              v-for="c in info.channels"
              :key="c.channel"
              data-testid="rec-channel"
              :class="{ muted: !c.hasSignal }"
            >
              <td style="width: 32px">
                <input
                  v-model="chosen[c.channel]!.on"
                  type="checkbox"
                  :disabled="!c.hasSignal"
                  :aria-label="c.name"
                  :data-testid="`rec-ch-${c.channel}`"
                >
              </td>
              <td style="width: 60px">
                #{{ c.channel }}
              </td>
              <td>
                <input
                  v-model="chosen[c.channel]!.name"
                  class="input"
                  :disabled="!c.hasSignal"
                >
              </td>
              <td class="small">
                <span
                  v-if="c.hasSignal"
                  class="badge badge-ok"
                >{{ c.resolution ? `${c.resolution.width}×${c.resolution.height}` : t('recorder.signal') }}</span>
                <span
                  v-else
                  class="badge badge-muted"
                >{{ t('recorder.noSignal') }}</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
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
        :disabled="busy || !info || count === 0 || !form.zoneId"
        data-testid="rec-save"
        @click="save"
      >
        {{ t('recorder.add', { n: count }) }}
      </button>
    </template>
  </AppModal>
</template>
