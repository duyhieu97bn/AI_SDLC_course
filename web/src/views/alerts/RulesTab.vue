<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import AppIcon from '@/components/AppIcon.vue'
import AppModal from '@/components/AppModal.vue'
import { alertWorkflowApi, cameraApi, orgApi, type Camera, type MotionRule, type SiteNode } from '@/services/api'
import { useToastStore } from '@/stores/toast'

/**
 * Quy tắc cảnh báo chuyển động (US-36, design/alerts.html tab "Quy tắc"): phạm vi, khung giờ theo giờ địa phương,
 * chống spam, kênh gửi. Camera không có chức năng phát hiện chuyển động bị ẩn khỏi danh sách chọn kèm lý do (AC4).
 */
const { t } = useI18n()
const toast = useToastStore()
const rules = ref<MotionRule[]>([])
const sites = ref<SiteNode[]>([])
const cameras = ref<Camera[]>([])
const editing = ref<MotionRule | null>(null)
const DAYS = [1, 2, 3, 4, 5, 6, 7]
const CHANNELS = ['WEB', 'EMAIL', 'TELEGRAM', 'ZALO']

const motionCameras = computed(() => cameras.value.filter((c) => c.capabilities?.motionDetection))
const hiddenCount = computed(() => cameras.value.length - motionCameras.value.length)

function scopeName(r: MotionRule) {
  if (r.scopeType === 'ORGANIZATION') return t('rules.scopeORGANIZATION')
  if (r.scopeType === 'SITE') return sites.value.find((s) => s.id === r.scopeId)?.name ?? '?'
  if (r.scopeType === 'ZONE') return sites.value.flatMap((s) => s.zones).find((z) => z.id === r.scopeId)?.name ?? '?'
  return cameras.value.find((c) => c.id === r.scopeId)?.name ?? '?'
}

async function load() {
  try {
    ;[rules.value, sites.value, cameras.value] = await Promise.all([
      alertWorkflowApi.motionRules(),
      orgApi.sites(),
      cameraApi.list({ size: 1000 }).then((p) => p.items),
    ])
  } catch (e) {
    toast.error(e)
  }
}

function edit(r?: MotionRule) {
  editing.value = r
    ? JSON.parse(JSON.stringify(r))
    : {
        name: '',
        enabled: true,
        scopeType: 'SITE',
        scopeId: sites.value[0]?.id ?? null,
        schedule: [{ days: [...DAYS], from: '22:00', to: '06:00' }],
        cooldownSeconds: 120,
        channels: ['WEB'],
        includeSnapshot: true,
      }
}

function toggle<T>(list: T[], v: T) {
  const i = list.indexOf(v)
  if (i >= 0) list.splice(i, 1)
  else list.push(v)
}

async function save() {
  if (!editing.value) return
  try {
    const r = { ...editing.value, scopeId: editing.value.scopeType === 'ORGANIZATION' ? null : editing.value.scopeId }
    await alertWorkflowApi.saveRule(r)
    editing.value = null
    toast.ok(t('common.saved'))
    await load()
  } catch (e) {
    toast.error(e)
  }
}

async function remove(r: MotionRule) {
  if (!r.id || !confirm(t('rules.confirmDelete', { name: r.name }))) return
  try {
    await alertWorkflowApi.deleteRule(r.id)
    await load()
  } catch (e) {
    toast.error(e)
  }
}

onMounted(load)
</script>

<template>
  <section class="card">
    <div class="card-head">
      <h3>{{ t('rules.title') }}</h3>
      <span class="spacer" />
      <button
        class="btn btn-sm btn-primary"
        data-testid="rule-new"
        @click="edit()"
      >
        <AppIcon
          name="plus"
          small
        />{{ t('rules.new') }}
      </button>
    </div>
    <div class="table-wrap">
      <table class="table">
        <thead>
          <tr>
            <th>{{ t('rules.name') }}</th>
            <th>{{ t('rules.scope') }}</th>
            <th>{{ t('rules.schedule') }}</th>
            <th>{{ t('rules.channels') }}</th>
            <th />
          </tr>
        </thead>
        <tbody>
          <tr v-if="rules.length === 0">
            <td
              colspan="5"
              class="muted"
            >
              {{ t('rules.empty') }}
            </td>
          </tr>
          <tr
            v-for="r in rules"
            :key="r.id"
            data-testid="rule-row"
          >
            <td>
              <b>{{ r.name }}</b>
              <span
                v-if="!r.enabled"
                class="badge badge-muted"
              >{{ t('rules.off') }}</span>
            </td>
            <td>{{ scopeName(r) }}</td>
            <td class="small">
              {{ r.schedule.length ? r.schedule.map((s) => `${s.from}–${s.to}`).join(', ') : t('rules.always') }}
              · {{ t('rules.cooldownShort', { n: r.cooldownSeconds }) }}
            </td>
            <td class="small">
              {{ r.channels.join(', ') }}
            </td>
            <td class="row">
              <button
                class="btn btn-sm"
                @click="edit(r)"
              >
                {{ t('common.edit') }}
              </button>
              <button
                class="btn btn-sm btn-ghost"
                @click="remove(r)"
              >
                {{ t('common.delete') }}
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>

  <AppModal
    v-if="editing"
    :title="editing.id ? t('common.edit') : t('rules.new')"
    @close="editing = null"
  >
    <div class="form-grid">
      <div class="field">
        <label for="r-name">{{ t('rules.name') }}</label>
        <input
          id="r-name"
          v-model="editing.name"
          class="input"
          maxlength="200"
          data-testid="rule-name"
        >
      </div>
      <div class="field">
        <label for="r-scope">{{ t('rules.scope') }}</label>
        <select
          id="r-scope"
          v-model="editing.scopeType"
          class="select"
          data-testid="rule-scope-type"
        >
          <option
            v-for="s in ['ORGANIZATION', 'SITE', 'ZONE', 'CAMERA']"
            :key="s"
            :value="s"
          >
            {{ t(`rules.scope${s}`) }}
          </option>
        </select>
      </div>
      <div
        v-if="editing.scopeType !== 'ORGANIZATION'"
        class="field"
        style="grid-column: 1 / -1"
      >
        <label for="r-target">{{ t('rules.target') }}</label>
        <select
          id="r-target"
          v-model="editing.scopeId"
          class="select"
          data-testid="rule-scope-id"
        >
          <template v-if="editing.scopeType === 'SITE'">
            <option
              v-for="s in sites"
              :key="s.id"
              :value="s.id"
            >
              {{ s.name }}
            </option>
          </template>
          <template v-else-if="editing.scopeType === 'ZONE'">
            <optgroup
              v-for="s in sites"
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
          </template>
          <template v-else>
            <option
              v-for="c in motionCameras"
              :key="c.id"
              :value="c.id"
            >
              {{ c.name }}
            </option>
          </template>
        </select>
        <p
          v-if="editing.scopeType === 'CAMERA' && hiddenCount > 0"
          class="small muted"
          data-testid="rule-hidden-hint"
        >
          {{ t('rules.hiddenCameras', { n: hiddenCount }) }}
        </p>
      </div>
    </div>
    <div
      v-for="(slot, i) in editing.schedule"
      :key="i"
      class="row"
    >
      <label
        v-for="dd in DAYS"
        :key="dd"
        class="row small"
      >
        <input
          type="checkbox"
          :checked="slot.days.includes(dd)"
          @change="toggle(slot.days, dd)"
        >{{ dd === 7 ? 'CN' : `T${dd + 1}` }}
      </label>
      <input
        v-model="slot.from"
        class="input"
        type="time"
        style="width: 120px"
        :data-testid="`rule-from-${i}`"
      >
      –
      <input
        v-model="slot.to"
        class="input"
        type="time"
        style="width: 120px"
        :data-testid="`rule-to-${i}`"
      >
      <button
        class="icon-btn"
        :aria-label="t('common.delete')"
        @click="editing.schedule.splice(i, 1)"
      >
        <AppIcon name="x" />
      </button>
    </div>
    <button
      class="btn btn-sm"
      @click="editing.schedule.push({ days: [...DAYS], from: '00:00', to: '00:00' })"
    >
      {{ t('rules.addSlot') }}
    </button>
    <p class="small muted">
      {{ t('rules.scheduleHint') }}
    </p>
    <div class="row">
      <label for="r-cool">{{ t('rules.cooldown') }}</label>
      <input
        id="r-cool"
        v-model.number="editing.cooldownSeconds"
        class="input"
        type="number"
        min="0"
        max="86400"
        style="width: 110px"
      >
      <label class="row small"><input
        v-model="editing.includeSnapshot"
        type="checkbox"
      >{{ t('rules.snapshot') }}</label>
      <label class="row small"><input
        v-model="editing.enabled"
        type="checkbox"
      >{{ t('rules.enabled') }}</label>
    </div>
    <div class="row">
      <b class="small">{{ t('rules.channels') }}</b>
      <label
        v-for="c in CHANNELS"
        :key="c"
        class="row small"
      >
        <input
          type="checkbox"
          :checked="editing.channels.includes(c)"
          :disabled="c === 'WEB'"
          :data-testid="`rule-ch-${c}`"
          @change="toggle(editing.channels, c)"
        >{{ c }}
      </label>
    </div>
    <template #footer>
      <button
        class="btn"
        @click="editing = null"
      >
        {{ t('common.cancel') }}
      </button>
      <button
        class="btn btn-primary"
        :disabled="!editing.name.trim()"
        data-testid="rule-save"
        @click="save"
      >
        {{ t('common.save') }}
      </button>
    </template>
  </AppModal>
</template>
