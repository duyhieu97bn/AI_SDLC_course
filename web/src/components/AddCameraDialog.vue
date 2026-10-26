<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import AppModal from './AppModal.vue'
import CameraPreview from './CameraPreview.vue'
import { deviceApi, type ConnectionResult, type SiteNode } from '@/services/api'
import { ApiError } from '@/services/http'
import { errorText } from '@/i18n'

/**
 * Thêm camera thủ công (US-02, design/cameras.html "Thêm thủ công"): ONVIF (IP/cổng/tài khoản) hoặc URL RTSP.
 * "Kiểm tra kết nối" hiện thông tin thiết bị và khung hình xem trước; chỉ lưu được khi kết nối thành công,
 * hoặc khi chọn "Lưu dù chưa kết nối" (AC2).
 */
const props = defineProps<{ sites: SiteNode[]; initialZoneId?: string }>()
const emit = defineEmits<{ close: []; saved: [] }>()
const { t } = useI18n()

const form = reactive({
  protocol: 'ONVIF' as 'ONVIF' | 'RTSP',
  host: '',
  port: 80,
  username: 'admin',
  password: '',
  streamUrl: '',
  name: '',
  zoneId: props.initialZoneId ?? '',
  saveUnverified: false,
})
const result = ref<ConnectionResult | null>(null)
const testing = ref(false)
const saving = ref(false)
const error = ref('')
const zones = computed(() => props.sites.filter((s) => s.manageable && s.zones.length))
const canSave = computed(() => !!form.zoneId && (result.value?.ok || form.saveUnverified))
const bFrames = computed(() => result.value?.streams.some((s) => s.bFrames) ?? false)

function connection() {
  const siteId = props.sites.find((s) => s.zones.some((z) => z.id === form.zoneId))?.id
  return form.protocol === 'ONVIF'
    ? { protocol: 'ONVIF' as const, host: form.host.trim(), port: form.port, username: form.username, password: form.password, siteId }
    : { protocol: 'RTSP' as const, streamUrl: form.streamUrl.trim(), username: form.username || undefined, password: form.password || undefined, siteId }
}

async function test() {
  testing.value = true
  error.value = ''
  result.value = null
  try {
    result.value = await deviceApi.testConnection(connection())
    if (result.value.ok && !form.name && result.value.model) form.name = `${result.value.model} ${form.host}`.trim()
  } catch (e) {
    error.value = e instanceof ApiError ? errorText(e.code, e.message) : String(e)
  } finally {
    testing.value = false
  }
}

async function save() {
  saving.value = true
  error.value = ''
  try {
    await deviceApi.create({ zoneId: form.zoneId, name: form.name || undefined, connection: connection(), saveUnverified: form.saveUnverified })
    emit('saved')
  } catch (e) {
    error.value = e instanceof ApiError ? errorText(e.code, e.message) : String(e)
  } finally {
    saving.value = false
  }
}

function resetResult() {
  result.value = null
}
</script>

<template>
  <AppModal
    :title="t('addCamera.title')"
    @close="emit('close')"
  >
    <div
      class="btn-group"
      role="group"
    >
      <button
        :class="{ active: form.protocol === 'ONVIF' }"
        data-testid="proto-onvif"
        @click="form.protocol = 'ONVIF'; resetResult()"
      >
        ONVIF
      </button>
      <button
        :class="{ active: form.protocol === 'RTSP' }"
        data-testid="proto-rtsp"
        @click="form.protocol = 'RTSP'; resetResult()"
      >
        RTSP
      </button>
    </div>
    <div class="form-grid">
      <template v-if="form.protocol === 'ONVIF'">
        <div class="field">
          <label for="c-host">{{ t('addCamera.host') }}</label>
          <input
            id="c-host"
            v-model="form.host"
            class="input mono"
            placeholder="192.168.1.64"
            @input="resetResult"
          >
        </div>
        <div class="field">
          <label for="c-port">{{ t('addCamera.port') }}</label>
          <input
            id="c-port"
            v-model.number="form.port"
            class="input mono"
            type="number"
            min="1"
            max="65535"
            @input="resetResult"
          >
        </div>
      </template>
      <div
        v-else
        class="field full"
      >
        <label for="c-url">{{ t('addCamera.rtspUrl') }}</label>
        <input
          id="c-url"
          v-model="form.streamUrl"
          class="input mono"
          placeholder="rtsp://192.168.1.64:554/Streaming/Channels/101"
          @input="resetResult"
        >
        <span class="hint">{{ t('addCamera.rtspHint') }}</span>
      </div>
      <div class="field">
        <label for="c-user">{{ t('addCamera.username') }}</label>
        <input
          id="c-user"
          v-model="form.username"
          class="input"
          autocomplete="off"
          @input="resetResult"
        >
      </div>
      <div class="field">
        <label for="c-pass">{{ t('addCamera.password') }}</label>
        <input
          id="c-pass"
          v-model="form.password"
          class="input"
          type="password"
          autocomplete="new-password"
          @input="resetResult"
        >
      </div>
    </div>
    <button
      class="btn"
      style="align-self: flex-start"
      :disabled="testing || (form.protocol === 'ONVIF' ? !form.host : !form.streamUrl)"
      data-testid="test-connection"
      @click="test"
    >
      {{ testing ? t('common.loading') : t('addCamera.test') }}
    </button>
    <div
      v-if="error"
      class="alert-box danger"
      data-testid="add-error"
    >
      {{ error }}
    </div>
    <div
      v-if="result && !result.ok"
      class="alert-box danger"
      data-testid="test-failed"
    >
      {{ result.message }}
    </div>
    <template v-if="result?.ok">
      <div
        class="alert-box info"
        data-testid="test-ok"
      >
        {{ t('addCamera.ok') }}
        <span v-if="result.manufacturer"> · {{ result.manufacturer }} {{ result.model }} · {{ result.firmware }}</span>
      </div>
      <div
        v-if="bFrames"
        class="alert-box warn"
      >
        {{ t('addCamera.bFrames') }}
      </div>
      <CameraPreview
        v-if="result.preview"
        :whep-url="result.preview.whepUrl"
        :hls-url="result.preview.hlsUrl"
        :token="result.preview.token"
      />
      <p class="small muted">
        <span
          v-for="s in result.streams"
          :key="s.type"
        >{{ s.type === 'MAIN' ? t('addCamera.main') : t('addCamera.sub') }}: {{ s.codec ?? '?' }}
          {{ s.width && s.height ? `${s.width}×${s.height}` : '' }} {{ s.fps ? `${s.fps} fps` : '' }}. </span>
      </p>
    </template>
    <div class="form-grid">
      <div class="field">
        <label for="c-name">{{ t('addCamera.name') }}</label>
        <input
          id="c-name"
          v-model="form.name"
          class="input"
          maxlength="200"
        >
      </div>
      <div class="field">
        <label for="c-zone">{{ t('addCamera.zone') }}</label>
        <select
          id="c-zone"
          v-model="form.zoneId"
          class="input"
        >
          <option
            value=""
            disabled
          >
            {{ t('common.choose') }}
          </option>
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
    </div>
    <label
      v-if="result && !result.ok"
      class="check small"
    >
      <input
        v-model="form.saveUnverified"
        type="checkbox"
        data-testid="save-unverified"
      > {{ t('addCamera.saveUnverified') }}
    </label>
    <template #footer>
      <button
        class="btn"
        @click="emit('close')"
      >
        {{ t('common.cancel') }}
      </button>
      <button
        class="btn btn-primary"
        :disabled="!canSave || saving"
        data-testid="save-camera"
        @click="save"
      >
        {{ t('addCamera.save') }}
      </button>
    </template>
  </AppModal>
</template>
