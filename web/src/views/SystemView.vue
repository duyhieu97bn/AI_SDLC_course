<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { alertApi, recordingApi, twoFactorApi, userApi, type AlertRule, type Role } from '@/services/api'
import BackupTab from '@/views/system/BackupTab.vue'
import ConnectorsTab from '@/views/system/ConnectorsTab.vue'
import ChannelsConfig from '@/views/system/ChannelsConfig.vue'
import { useToastStore } from '@/stores/toast'

/**
 * Hệ thống (design/system.html). S2: mục Bảo mật – bắt buộc 2FA theo vai trò (US-31 AC1).
 * S5: quy tắc cảnh báo mất kết nối/lưu trữ (US-34, US-35), sao lưu chỉ xem (US-40).
 */
const { t } = useI18n()
const toast = useToastStore()
const roles = ref<Role[]>([])
const retentionDays = ref(30)
const rules = ref<AlertRule[]>([])

async function load() {
  roles.value = await userApi.roles()
  retentionDays.value = (await recordingApi.retention()).defaultRetentionDays
  rules.value = await alertApi.rules()
}

async function saveRule(r: AlertRule) {
  try {
    await alertApi.setRule(r)
    toast.ok(t('common.saved'))
  } catch (e) {
    toast.error(e)
  }
}

async function saveRetention() {
  try {
    await recordingApi.setRetention(retentionDays.value)
    toast.ok(t('common.saved'))
  } catch (e) {
    toast.error(e)
  }
}

async function toggle(r: Role, required: boolean) {
  try {
    await twoFactorApi.setRoleRequired(r.id, required)
    toast.ok(t('common.saved'))
    await load()
  } catch (e) {
    toast.error(e)
  }
}

onMounted(() => void load().catch((e) => toast.error(e)))
</script>

<template>
  <section class="card">
    <div class="card-head">
      <h3>{{ t('system.storage') }}</h3>
    </div>
    <div class="card-body row">
      <label for="ret-days">{{ t('retention.default') }}</label>
      <input
        id="ret-days"
        v-model.number="retentionDays"
        class="input"
        type="number"
        min="1"
        max="3650"
        style="width: 120px"
      >
      <button
        class="btn"
        data-testid="save-retention"
        @click="saveRetention"
      >
        {{ t('common.save') }}
      </button>
      <span class="small muted">{{ t('retention.hint') }}</span>
    </div>
  </section>
  <section class="card">
    <div class="card-head">
      <h3>{{ t('system.alertRules') }}</h3>
    </div>
    <div class="card-body stack">
      <div
        v-for="r in rules"
        :key="r.type"
        class="row"
        :data-testid="`rule-${r.type}`"
      >
        <label class="switch">
          <input
            v-model="r.enabled"
            type="checkbox"
          >
          <span />
        </label>
        <b style="min-width: 200px">{{ t(`alerts.type${r.type}`) }}</b>
        <template v-if="r.type === 'STORAGE_THRESHOLD'">
          <label :for="`w-${r.type}`">{{ t('system.warningPercent') }}</label>
          <input
            :id="`w-${r.type}`"
            v-model.number="r.params.warningPercent"
            class="input"
            type="number"
            min="1"
            max="99"
            style="width: 90px"
            data-testid="warning-percent"
          >
          <label :for="`c-${r.type}`">{{ t('system.criticalPercent') }}</label>
          <input
            :id="`c-${r.type}`"
            v-model.number="r.params.criticalPercent"
            class="input"
            type="number"
            min="2"
            max="100"
            style="width: 90px"
          >
        </template>
        <template v-else>
          <label :for="`d-${r.type}`">{{ t('system.offlineDelay') }}</label>
          <input
            :id="`d-${r.type}`"
            v-model.number="r.params.offlineDelayMinutes"
            class="input"
            type="number"
            min="1"
            max="1440"
            style="width: 90px"
          >
        </template>
        <label class="row small">
          <input
            type="checkbox"
            :checked="r.channels.includes('EMAIL')"
            @change="r.channels = ($event.target as HTMLInputElement).checked ? ['WEB', 'EMAIL'] : ['WEB']"
          >{{ t('system.email') }}
        </label>
        <button
          class="btn btn-sm"
          :data-testid="`save-rule-${r.type}`"
          @click="saveRule(r)"
        >
          {{ t('common.save') }}
        </button>
      </div>
    </div>
  </section>
  <ChannelsConfig />
  <ConnectorsTab />
  <BackupTab />
  <section class="card">
    <div class="card-head">
      <h3>{{ t('system.security') }}</h3>
    </div>
    <div class="card-body stack">
      <p class="small muted">
        {{ t('system.require2faHint') }}
      </p>
      <div
        v-for="r in roles"
        :key="r.id"
        class="row"
      >
        <label class="switch">
          <input
            type="checkbox"
            :checked="r.requireTwoFactor"
            :data-testid="`require-2fa-${r.code ?? r.id}`"
            @change="toggle(r, ($event.target as HTMLInputElement).checked)"
          >
          <span />
        </label>
        <b>{{ r.name }}</b>
        <span class="small muted">{{ t('system.usersCount', { n: r.assignedUsers }) }}</span>
      </div>
    </div>
  </section>
</template>
