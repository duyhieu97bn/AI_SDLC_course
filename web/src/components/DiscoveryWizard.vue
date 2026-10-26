<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import AppModal from './AppModal.vue'
import { deviceApi, type BulkResult, type Discovered, type SiteNode } from '@/services/api'
import { ApiError } from '@/services/http'
import { errorText } from '@/i18n'

/**
 * Dò tìm ONVIF 4 bước (US-01, design/cameras.html): chọn địa điểm → danh sách tìm thấy (camera đã có bị đánh dấu
 * "Đã thêm", không chọn được) → tài khoản chung và khu vực → kết quả từng camera, camera lỗi có nút nhập lại.
 */
const props = defineProps<{ sites: SiteNode[] }>()
const emit = defineEmits<{ close: []; done: [] }>()
const { t } = useI18n()

const step = ref(1)
const sites = computed(() => props.sites.filter((s) => s.manageable))
const siteId = ref(sites.value[0]?.id ?? '')
const targets = ref('')
const found = ref<Discovered[]>([])
const selected = ref<Set<string>>(new Set())
const username = ref('admin')
const password = ref('')
const zoneId = ref('')
const results = ref<BulkResult[]>([])
const retryPassword = ref<Record<string, string>>({})
const busy = ref(false)
const error = ref('')

const site = computed(() => props.sites.find((s) => s.id === siteId.value))
const failed = computed(() => results.value.filter((r) => !r.ok))

async function scan() {
  busy.value = true
  error.value = ''
  try {
    found.value = await deviceApi.discover(
      siteId.value,
      targets.value.split(/[\s,;]+/).map((x) => x.trim()).filter(Boolean),
    )
    selected.value = new Set(found.value.filter((f) => !f.alreadyAdded).map((f) => f.host))
    zoneId.value = site.value?.zones[0]?.id ?? ''
    step.value = 2
  } catch (e) {
    error.value = e instanceof ApiError ? errorText(e.code, e.message) : String(e)
  } finally {
    busy.value = false
  }
}

function toggle(host: string) {
  const s = new Set(selected.value)
  if (s.has(host)) s.delete(host)
  else s.add(host)
  selected.value = s
}

async function add(devices: Discovered[], user: string, pass: string) {
  return deviceApi.bulk({
    zoneId: zoneId.value,
    username: user,
    password: pass,
    devices: devices.map((d) => ({ host: d.host, port: d.port, name: d.model ? `${d.model} ${d.host}` : undefined })),
  })
}

async function submit() {
  busy.value = true
  error.value = ''
  try {
    results.value = await add(found.value.filter((f) => selected.value.has(f.host)), username.value, password.value)
    step.value = 4
  } catch (e) {
    error.value = e instanceof ApiError ? errorText(e.code, e.message) : String(e)
  } finally {
    busy.value = false
  }
}

async function retry(host: string) {
  const dev = found.value.find((f) => f.host === host)
  if (!dev) return
  const [r] = await add([dev], username.value, retryPassword.value[host] ?? '')
  if (r) results.value = results.value.map((x) => (x.host === host ? r : x))
}
</script>

<template>
  <AppModal
    :title="t('discovery.title')"
    @close="emit('close')"
  >
    <div class="steps">
      <span
        v-for="(label, i) in [t('discovery.s1'), t('discovery.s2'), t('discovery.s3'), t('discovery.s4')]"
        :key="i"
        :class="['step', { done: step > i + 1, current: step === i + 1 }]"
      ><b>{{ i + 1 }}</b>{{ label }}</span>
    </div>
    <div
      v-if="error"
      class="alert-box danger"
    >
      {{ error }}
    </div>

    <template v-if="step === 1">
      <div class="field">
        <label for="d-site">{{ t('discovery.site') }}</label>
        <select
          id="d-site"
          v-model="siteId"
          class="input"
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
        <label for="d-targets">{{ t('discovery.targets') }}</label>
        <input
          id="d-targets"
          v-model="targets"
          class="input mono"
          placeholder="192.168.1.0/24"
        >
        <span class="hint">{{ t('discovery.targetsHint') }}</span>
      </div>
    </template>

    <template v-else-if="step === 2">
      <p class="small muted">
        {{ t('discovery.found', { n: found.length }) }}
      </p>
      <div class="table-wrap">
        <table class="table">
          <tbody>
            <tr
              v-for="f in found"
              :key="f.host"
              data-testid="found-row"
            >
              <td>
                <input
                  type="checkbox"
                  :disabled="f.alreadyAdded"
                  :checked="selected.has(f.host)"
                  :aria-label="f.host"
                  @change="toggle(f.host)"
                >
              </td>
              <td class="mono">
                {{ f.host }}:{{ f.port }}
              </td>
              <td>{{ f.manufacturer }} {{ f.model }}</td>
              <td class="mono small hide-sm">
                {{ f.macAddress }}
              </td>
              <td>
                <span
                  v-if="f.alreadyAdded"
                  class="badge badge-muted"
                >{{ t('discovery.added') }}</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>

    <template v-else-if="step === 3">
      <div class="form-grid">
        <div class="field">
          <label for="d-user">{{ t('addCamera.username') }}</label>
          <input
            id="d-user"
            v-model="username"
            class="input"
          >
        </div>
        <div class="field">
          <label for="d-pass">{{ t('addCamera.password') }}</label>
          <input
            id="d-pass"
            v-model="password"
            class="input"
            type="password"
            autocomplete="new-password"
          >
        </div>
        <div class="field full">
          <label for="d-zone">{{ t('addCamera.zone') }}</label>
          <select
            id="d-zone"
            v-model="zoneId"
            class="input"
          >
            <option
              v-for="z in site?.zones ?? []"
              :key="z.id"
              :value="z.id"
            >
              {{ z.name }}
            </option>
          </select>
        </div>
      </div>
    </template>

    <template v-else>
      <p class="small">
        {{ t('discovery.summary', { ok: results.length - failed.length, total: results.length }) }}
      </p>
      <div
        v-for="r in results"
        :key="r.host"
        class="row"
        data-testid="bulk-result"
      >
        <span class="mono">{{ r.host }}</span>
        <span :class="['badge', r.ok ? 'badge-ok' : 'badge-danger']">{{ r.ok ? t('common.done') : r.message }}</span>
        <template v-if="!r.ok && r.code === 'AUTH_FAILED'">
          <input
            v-model="retryPassword[r.host]"
            class="input"
            type="password"
            style="width: 160px; height: 28px"
            :placeholder="t('addCamera.password')"
          >
          <button
            class="btn btn-sm"
            @click="retry(r.host)"
          >
            {{ t('discovery.retry') }}
          </button>
        </template>
      </div>
    </template>

    <template #footer>
      <button
        class="btn"
        @click="step === 4 ? emit('done') : emit('close')"
      >
        {{ step === 4 ? t('common.close') : t('common.cancel') }}
      </button>
      <button
        v-if="step === 1"
        class="btn btn-primary"
        :disabled="!siteId || busy"
        data-testid="discovery-scan"
        @click="scan"
      >
        {{ busy ? t('discovery.scanning') : t('discovery.scan') }}
      </button>
      <button
        v-if="step === 2"
        class="btn btn-primary"
        :disabled="selected.size === 0"
        data-testid="discovery-next"
        @click="step = 3"
      >
        {{ t('discovery.next', { n: selected.size }) }}
      </button>
      <button
        v-if="step === 3"
        class="btn btn-primary"
        :disabled="!zoneId || busy"
        data-testid="discovery-add"
        @click="submit"
      >
        {{ busy ? t('common.loading') : t('discovery.add') }}
      </button>
    </template>
  </AppModal>
</template>
