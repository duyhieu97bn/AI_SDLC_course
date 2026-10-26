<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import AppModal from '@/components/AppModal.vue'
import { networkApi, type Camera, type NetworkInfo } from '@/services/api'
import { useToastStore } from '@/stores/toast'

/**
 * Mạng & thời gian (US-25, design/camera-detail.html): đổi IP tĩnh/DHCP (cảnh báo có thể mất kết nối tạm thời trước khi
 * áp – AC1), nguồn giờ NTP; hiển thị độ lệch đồng hồ, lệch > 5 giây được đánh dấu (AC2).
 */
const props = defineProps<{ camera: Camera }>()
const emit = defineEmits<{ changed: [] }>()
const { t } = useI18n()
const toast = useToastStore()
const info = ref<NetworkInfo | null>(null)
const form = ref({ dhcp: false, address: '', prefixLength: 24, gateway: '' })
const ntp = ref('')
const confirmOpen = ref(false)
const busy = ref(false)

async function load() {
  try {
    info.value = await networkApi.get(props.camera.id)
    form.value = {
      dhcp: info.value.fromDhcp,
      address: info.value.ipAddress ?? '',
      prefixLength: info.value.prefixLength ?? 24,
      gateway: info.value.gateway ?? '',
    }
    ntp.value = info.value.ntpServers.join(', ')
  } catch (e) {
    toast.error(e)
  }
}

async function applyNetwork() {
  confirmOpen.value = false
  busy.value = true
  try {
    const r = await networkApi.set(props.camera.id, form.value.dhcp
      ? { dhcp: true }
      : { dhcp: false, address: form.value.address.trim(), prefixLength: form.value.prefixLength, gateway: form.value.gateway.trim() || null })
    toast.ok(r.newHost ? t('network.changedTo', { host: r.newHost }) : t('common.saved'))
    emit('changed')
  } catch (e) {
    toast.error(e)
  } finally {
    busy.value = false
  }
}

async function applyNtp() {
  busy.value = true
  try {
    const servers = ntp.value.split(',').map((x) => x.trim()).filter(Boolean)
    info.value = await networkApi.setTime(props.camera.id, { mode: 'NTP', ntpServers: servers.length ? servers : undefined })
    toast.ok(t('common.saved'))
  } catch (e) {
    toast.error(e)
  } finally {
    busy.value = false
  }
}

onMounted(load)
</script>

<template>
  <section
    v-if="info"
    class="card"
    data-testid="network-tab"
  >
    <div class="card-body stack">
      <div class="row">
        <b>{{ t('network.clock') }}</b>
        <span>{{ new Date(info.deviceTime).toLocaleString() }}</span>
        <span
          :class="['badge', Math.abs(info.clockDriftSeconds) > 5 ? 'badge-warn' : 'badge-ok']"
          data-testid="clock-drift"
        >{{ t('network.drift', { s: info.clockDriftSeconds.toFixed(1) }) }}</span>
      </div>
      <div class="row">
        <label for="net-ntp">NTP</label>
        <input
          id="net-ntp"
          v-model="ntp"
          class="input"
          style="max-width: 320px"
          placeholder="pool.ntp.org"
          data-testid="net-ntp"
        >
        <button
          class="btn btn-sm"
          :disabled="busy"
          data-testid="net-ntp-apply"
          @click="applyNtp"
        >
          {{ t('network.syncNtp') }}
        </button>
      </div>
      <hr>
      <label class="row"><input
        v-model="form.dhcp"
        type="checkbox"
      >DHCP</label>
      <div
        v-if="!form.dhcp"
        class="form-grid"
      >
        <div class="field">
          <label for="net-ip">{{ t('network.address') }}</label>
          <input
            id="net-ip"
            v-model="form.address"
            class="input"
            data-testid="net-ip"
          >
        </div>
        <div class="field">
          <label for="net-prefix">{{ t('network.prefix') }}</label>
          <input
            id="net-prefix"
            v-model.number="form.prefixLength"
            class="input"
            type="number"
            min="1"
            max="32"
          >
        </div>
        <div class="field">
          <label for="net-gw">{{ t('network.gateway') }}</label>
          <input
            id="net-gw"
            v-model="form.gateway"
            class="input"
          >
        </div>
      </div>
      <div class="row">
        <button
          class="btn btn-primary"
          :disabled="busy"
          data-testid="net-apply"
          @click="confirmOpen = true"
        >
          {{ t('common.apply') }}
        </button>
        <span class="small muted">MAC {{ info.macAddress }}</span>
      </div>
    </div>
  </section>

  <AppModal
    v-if="confirmOpen"
    :title="t('network.confirmTitle')"
    size="sm"
    @close="confirmOpen = false"
  >
    <p data-testid="net-warning">
      {{ t('network.warning') }}
    </p>
    <template #footer>
      <button
        class="btn"
        @click="confirmOpen = false"
      >
        {{ t('common.cancel') }}
      </button>
      <button
        class="btn btn-primary"
        data-testid="net-confirm"
        @click="applyNetwork"
      >
        {{ t('common.apply') }}
      </button>
    </template>
  </AppModal>
</template>
