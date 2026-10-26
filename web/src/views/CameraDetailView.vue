<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import AppIcon from '@/components/AppIcon.vue'
import ZoneEditor from '@/components/ZoneEditor.vue'
import ImageTab from '@/views/camera/ImageTab.vue'
import NetworkTab from '@/views/camera/NetworkTab.vue'
import {
  cameraApi,
  deviceApi,
  maintenanceApi,
  recordingApi,
  type ApplyResult,
  type Camera,
  type EncoderConfig,
  type EvidenceHold,
  type RecordingMode,
  type RecordingPolicy,
  type ScheduleSlot,
  type StreamInfo,
} from '@/services/api'
import { useAuthStore } from '@/stores/auth'
import { useToastStore } from '@/stores/toast'
import { useRealtimeStore } from '@/stores/realtime'

/**
 * Chi tiết camera (design/camera-detail.html): Thông tin, Ghi hình (US-16), Luồng video (US-23, chỉ tham số camera hỗ
 * trợ – US-29), Vùng chuyển động/vùng che (US-26), Lưu trữ (US-21: ghi đè số ngày, khoảng giữ lại).
 */
const { t, d } = useI18n()
const route = useRoute()
const auth = useAuthStore()
const toast = useToastStore()
const id = computed(() => String(route.params.id))

type Tab = 'info' | 'recording' | 'stream' | 'image' | 'zones' | 'network' | 'storage'
const tab = ref<Tab>('info')
const camera = ref<Camera | null>(null)
const streams = ref<StreamInfo[]>([])
const policy = ref<RecordingPolicy | null>(null)
const encoders = ref<EncoderConfig[]>([])
const edits = ref<Record<string, Partial<EncoderConfig>>>({})
const applyResult = ref<ApplyResult | null>(null)
const retention = ref<{ override: number | ''; defaultRetentionDays: number } | null>(null)
const overrideDays = ref<number | ''>('')
const holds = ref<EvidenceHold[]>([])
const DAYS = ['T2', 'T3', 'T4', 'T5', 'T6', 'T7', 'CN']
const canConfigure = computed(() => auth.can('CONFIGURE_CAMERA'))
const realtime = useRealtimeStore()
/** Khởi động lại camera (US-28 AC2): theo dõi đến khi camera trực tuyến lại */
const rebooting = ref(false)

async function reboot() {
  if (!camera.value || !confirm(t('maintenance.confirmReboot', { name: camera.value.name }))) return
  try {
    await maintenanceApi.reboot(camera.value.id)
    rebooting.value = true
    toast.push('info', t('maintenance.rebooting'))
  } catch (e) {
    toast.error(e)
  }
}

watch(
  () => realtime.rebootUpdate,
  (u) => {
    if (!u || u.cameraId !== id.value) return
    rebooting.value = false
    if (u.state === 'ONLINE') toast.ok(t('maintenance.backOnline', { name: u.name }))
    else toast.push('warn', t('maintenance.rebootTimeout', { name: u.name }))
  },
)

async function load() {
  try {
    camera.value = await cameraApi.get(id.value)
    streams.value = await deviceApi.streams(id.value).catch(() => [])
  } catch (e) {
    toast.error(e)
  }
}

async function loadTab(tb: Tab) {
  try {
    if (tb === 'recording' && !policy.value) policy.value = await recordingApi.policy(id.value)
    if (tb === 'stream') {
      encoders.value = await recordingApi.streamConfig(id.value)
      edits.value = Object.fromEntries(encoders.value.map((e) => [e.token, { ...e }]))
    }
    if (tb === 'storage') {
      retention.value = await recordingApi.cameraRetention(id.value)
      overrideDays.value = retention.value.override
      holds.value = await recordingApi.holds(id.value)
    }
  } catch (e) {
    toast.error(e)
  }
}

async function savePolicy() {
  if (!policy.value) return
  try {
    const { mode, schedule, preEventSeconds, postEventSeconds } = policy.value
    policy.value = await recordingApi.setPolicy(id.value, { mode, schedule, preEventSeconds, postEventSeconds })
    toast.ok(t('common.saved'))
  } catch (e) {
    toast.error(e)
  }
}

function addSlot() {
  policy.value?.schedule.push({ day: 0, from: '18:00', to: '24:00' } as ScheduleSlot)
}

async function applyStream() {
  const changes = encoders.value.map((e) => {
    const n = edits.value[e.token]!
    return {
      configurationToken: e.token,
      codec: n.codec !== e.codec ? n.codec : undefined,
      width: n.width !== e.width || n.height !== e.height ? n.width : undefined,
      height: n.width !== e.width || n.height !== e.height ? n.height : undefined,
      fps: n.fps !== e.fps ? n.fps : undefined,
      bitrateKbps: n.bitrateKbps !== e.bitrateKbps ? n.bitrateKbps : undefined,
      govLength: n.govLength !== e.govLength ? (n.govLength ?? undefined) : undefined,
    }
  }).filter((c) => Object.values(c).filter((v) => v !== undefined).length > 1)
  if (!changes.length) return
  try {
    applyResult.value = await recordingApi.applyStream(id.value, changes)
    await loadTab('stream')
  } catch (e) {
    toast.error(e)
  }
}

function setResolution(token: string, v: string) {
  const [w, h] = v.split('x').map(Number)
  edits.value[token] = { ...edits.value[token], width: w, height: h }
}

async function saveRetention() {
  try {
    await recordingApi.setCameraRetention(id.value, overrideDays.value === '' ? null : Number(overrideDays.value))
    toast.ok(t('common.saved'))
  } catch (e) {
    toast.error(e)
  }
}

async function release(h: EvidenceHold) {
  try {
    await recordingApi.release(h.id)
    holds.value = await recordingApi.holds(id.value)
  } catch (e) {
    toast.error(e)
  }
}

watch(tab, (tb) => void loadTab(tb))
onMounted(load)
</script>

<template>
  <div
    v-if="camera"
    class="stack"
  >
    <div class="row">
      <RouterLink
        class="btn btn-sm btn-ghost"
        to="/cameras"
      >
        <AppIcon
          name="left"
          small
        />{{ t('nav.cameras') }}
      </RouterLink>
      <h2 data-testid="camera-title">
        {{ camera.name }}
      </h2>
      <span :class="['badge', camera.status === 'ONLINE' ? 'badge-ok' : camera.status === 'OFFLINE' ? 'badge-danger' : 'badge-muted']">
        {{ t(`cameras.status${camera.status}`) }}
      </span>
      <span class="spacer" />
      <span
        v-if="rebooting"
        class="badge badge-warn"
        data-testid="rebooting"
      >{{ t('maintenance.rebooting') }}</span>
      <button
        v-if="canConfigure && camera.protocol === 'ONVIF'"
        class="btn btn-sm"
        data-testid="reboot"
        :disabled="rebooting"
        @click="reboot"
      >
        <AppIcon
          name="power"
          small
        />{{ t('maintenance.reboot') }}
      </button>
      <RouterLink
        v-if="auth.can('VIEW_PLAYBACK')"
        class="btn btn-sm"
        :to="{ path: '/playback', query: { camera: camera.id } }"
      >
        <AppIcon
          name="history"
          small
        />{{ t('nav.playback') }}
      </RouterLink>
    </div>
    <div class="tabs">
      <button
        v-for="tb in (['info', 'recording', 'stream', 'image', 'zones', 'network', 'storage'] as Tab[])"
        :key="tb"
        :class="['tab', { active: tab === tb }]"
        :data-testid="`tab-${tb}`"
        :disabled="['stream', 'zones', 'image', 'network'].includes(tb) && camera.protocol !== 'ONVIF'"
        @click="tab = tb"
      >
        {{ t(`cameraDetail.tab.${tb}`) }}
      </button>
    </div>

    <NetworkTab
      v-if="tab === 'network'"
      :camera="camera"
      @changed="load"
    />
    <ImageTab
      v-if="tab === 'image'"
      :camera="camera"
    />
    <ZoneEditor
      v-if="tab === 'zones'"
      :camera="camera"
    />
    <section
      v-if="tab === 'info'"
      class="card"
    >
      <div class="card-body form-grid">
        <div class="field">
          <label>{{ t('cameraDetail.protocol') }}</label><span>{{ camera.protocol }}</span>
        </div>
        <div class="field">
          <label>{{ t('cameras.model') }}</label><span>{{ [camera.manufacturer, camera.model].filter(Boolean).join(' ') || '—' }}</span>
        </div>
        <div class="field">
          <label>IP</label><span class="mono">{{ camera.ipAddress }}{{ camera.port ? `:${camera.port}` : '' }}</span>
        </div>
        <div class="field">
          <label>MAC</label><span class="mono">{{ camera.macAddress ?? '—' }}</span>
        </div>
        <div class="field">
          <label>Firmware</label><span>{{ camera.firmware ?? '—' }}</span>
        </div>
        <div class="field">
          <label>{{ t('cameraDetail.capabilities') }}</label>
          <span>{{ Object.entries(camera.capabilities ?? {}).filter(([, v]) => v).map(([k]) => k).join(', ') || '—' }}</span>
        </div>
        <div class="field full">
          <label>{{ t('cameraDetail.streams') }}</label>
          <span
            v-for="s in streams"
            :key="s.type"
          >{{ s.type }}: {{ s.codec }} {{ s.width && s.height ? `${s.width}×${s.height}` : '' }} {{ s.fps ? `${s.fps} fps` : '' }}
            {{ s.bitrateKbps ? `${s.bitrateKbps} Kbps` : '' }} <b v-if="s.bFrames">· B-frame</b></span>
        </div>
      </div>
    </section>

    <section
      v-if="tab === 'recording' && policy"
      class="card"
    >
      <div class="card-body stack">
        <div
          class="btn-group"
          role="group"
        >
          <button
            v-for="m in (['CONTINUOUS', 'SCHEDULED', 'EVENT', 'OFF'] as RecordingMode[])"
            :key="m"
            :class="{ active: policy.mode === m }"
            :disabled="!canConfigure"
            :data-testid="`mode-${m}`"
            @click="policy.mode = m"
          >
            {{ t(`recording.mode${m}`) }}
          </button>
        </div>
        <div
          v-if="policy.mode === 'EVENT'"
          class="form-grid"
        >
          <div class="field">
            <label for="pre">{{ t('recording.pre') }}</label>
            <input
              id="pre"
              v-model.number="policy.preEventSeconds"
              class="input"
              type="number"
              min="0"
              max="60"
            >
          </div>
          <div class="field">
            <label for="post">{{ t('recording.post') }}</label>
            <input
              id="post"
              v-model.number="policy.postEventSeconds"
              class="input"
              type="number"
              min="0"
              max="600"
            >
          </div>
        </div>
        <div
          v-if="policy.mode === 'SCHEDULED'"
          class="stack"
        >
          <div
            v-for="(s, i) in policy.schedule"
            :key="i"
            class="row"
          >
            <select
              v-model.number="s.day"
              class="input"
              style="width: auto"
            >
              <option
                v-for="(dname, di) in DAYS"
                :key="di"
                :value="di"
              >
                {{ dname }}
              </option>
            </select>
            <input
              v-model="s.from"
              class="input"
              type="time"
              style="width: auto"
            >
            <span>–</span>
            <input
              v-model="s.to"
              class="input"
              style="width: 90px"
              placeholder="24:00"
            >
            <button
              class="icon-btn"
              :aria-label="t('common.delete')"
              @click="policy.schedule.splice(i, 1)"
            >
              <AppIcon name="trash" />
            </button>
          </div>
          <button
            class="btn btn-sm"
            style="align-self: flex-start"
            @click="addSlot"
          >
            <AppIcon
              name="plus"
              small
            />{{ t('recording.addSlot') }}
          </button>
        </div>
        <button
          v-if="canConfigure"
          class="btn btn-primary"
          style="align-self: flex-start"
          data-testid="save-policy"
          @click="savePolicy"
        >
          {{ t('common.save') }}
        </button>
      </div>
    </section>

    <section
      v-if="tab === 'stream'"
      class="card"
    >
      <div class="card-body stack">
        <div
          v-for="e in encoders"
          :key="e.token"
          class="stack"
        >
          <h3>{{ e.name }}</h3>
          <div class="form-grid">
            <div
              v-if="e.options?.codecs?.length"
              class="field"
            >
              <label>Codec</label>
              <select
                v-model="edits[e.token]!.codec"
                class="input"
              >
                <option
                  v-for="c in e.options.codecs"
                  :key="c"
                  :value="c"
                >
                  {{ c }}
                </option>
              </select>
            </div>
            <div
              v-if="e.options?.resolutions?.length"
              class="field"
            >
              <label>{{ t('cameraDetail.resolution') }}</label>
              <select
                class="input"
                :value="`${edits[e.token]!.width}x${edits[e.token]!.height}`"
                @change="setResolution(e.token, ($event.target as HTMLSelectElement).value)"
              >
                <option
                  v-for="r in e.options.resolutions"
                  :key="`${r.width}x${r.height}`"
                  :value="`${r.width}x${r.height}`"
                >
                  {{ r.width }}×{{ r.height }}
                </option>
              </select>
            </div>
            <div
              v-if="e.options?.fpsRange"
              class="field"
            >
              <label>FPS ({{ e.options.fpsRange.min }}–{{ e.options.fpsRange.max }})</label>
              <input
                v-model.number="edits[e.token]!.fps"
                class="input"
                type="number"
                :min="e.options.fpsRange.min"
                :max="e.options.fpsRange.max"
                :data-testid="`fps-${e.token}`"
              >
            </div>
            <div
              v-if="e.options?.bitrateRange"
              class="field"
            >
              <label>Bitrate Kbps ({{ e.options.bitrateRange.min }}–{{ e.options.bitrateRange.max }})</label>
              <input
                v-model.number="edits[e.token]!.bitrateKbps"
                class="input"
                type="number"
                :min="e.options.bitrateRange.min"
                :max="e.options.bitrateRange.max"
              >
            </div>
            <div
              v-if="e.options?.govLengthRange"
              class="field"
            >
              <label>GOP ({{ e.options.govLengthRange.min }}–{{ e.options.govLengthRange.max }})</label>
              <input
                v-model.number="edits[e.token]!.govLength"
                class="input"
                type="number"
              >
            </div>
          </div>
        </div>
        <button
          v-if="canConfigure"
          class="btn btn-primary"
          style="align-self: flex-start"
          data-testid="apply-stream"
          @click="applyStream"
        >
          {{ t('common.apply') }}
        </button>
        <div
          v-if="applyResult"
          :class="['alert-box', applyResult.result === 'SUCCESS' ? 'info' : applyResult.result === 'PARTIAL' ? 'warn' : 'danger']"
          data-testid="apply-result"
        >
          <div>
            <b>{{ t(`cameraDetail.result${applyResult.result}`) }}</b>
            <ul class="small">
              <li
                v-for="(p, i) in applyResult.parameters"
                :key="i"
              >
                {{ p.parameter }}: {{ p.ok ? t('common.done') : p.message }}
              </li>
            </ul>
          </div>
        </div>
      </div>
    </section>

    <section
      v-if="tab === 'storage' && retention"
      class="card"
    >
      <div class="card-body stack">
        <div class="field">
          <label for="ov">{{ t('retention.override', { d: retention.defaultRetentionDays }) }}</label>
          <div class="row">
            <input
              id="ov"
              v-model="overrideDays"
              class="input"
              type="number"
              min="1"
              max="3650"
              style="width: 140px"
              :placeholder="String(retention.defaultRetentionDays)"
              :disabled="!canConfigure"
            >
            <button
              v-if="canConfigure"
              class="btn"
              @click="saveRetention"
            >
              {{ t('common.save') }}
            </button>
          </div>
        </div>
        <h3>{{ t('retention.holds') }}</h3>
        <div class="table-wrap">
          <table class="table">
            <tbody>
              <tr
                v-for="h in holds"
                :key="h.id"
              >
                <td class="mono">
                  {{ d(new Date(h.startAt), 'short') }} – {{ d(new Date(h.endAt), 'short') }}
                </td>
                <td class="wrap">
                  {{ h.reason }}
                </td>
                <td>
                  <span
                    v-if="h.releasedAt"
                    class="badge badge-muted"
                  >{{ t('retention.released') }}</span>
                  <button
                    v-else-if="auth.can('HOLD_EVIDENCE')"
                    class="btn btn-sm"
                    @click="release(h)"
                  >
                    {{ t('retention.release') }}
                  </button>
                </td>
              </tr>
              <tr v-if="holds.length === 0">
                <td class="muted">
                  {{ t('retention.noHolds') }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </section>
  </div>
</template>
