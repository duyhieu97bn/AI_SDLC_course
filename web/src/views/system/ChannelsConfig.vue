<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { channelApi, type ChannelConfig } from '@/services/api'
import { useToastStore } from '@/stores/toast'

/**
 * Kênh thông báo của Tổ chức (US-36.4, design/system.html "Kênh thông báo"): bật Telegram (token bot) và Zalo OA
 * (access token). Token lưu mã hóa, không hiển thị lại; để trống khi lưu là giữ token cũ.
 */
const { t } = useI18n()
const toast = useToastStore()
const CHANNELS = ['TELEGRAM', 'ZALO'] as const
const configs = ref<Record<string, ChannelConfig>>({})
const secrets = ref<Record<string, string>>({ TELEGRAM: '', ZALO: '' })
const enabled = ref<Record<string, boolean>>({ TELEGRAM: false, ZALO: false })
const loaded = ref(false)

async function load() {
  try {
    const list = await channelApi.configs()
    configs.value = Object.fromEntries(list.map((c) => [c.channel, c]))
    for (const c of CHANNELS) enabled.value[c] = configs.value[c]?.enabled ?? false
    loaded.value = true
  } catch (e) {
    toast.error(e)
  }
}

async function save(channel: string) {
  try {
    await channelApi.configure(channel, !!enabled.value[channel], secrets.value[channel] || undefined)
    secrets.value[channel] = ''
    toast.ok(t('common.saved'))
    await load()
  } catch (e) {
    toast.error(e)
  }
}

onMounted(load)
</script>

<template>
  <section
    class="card"
    data-testid="channels-config"
    :data-loaded="loaded"
  >
    <div class="card-head">
      <h3>{{ t('channels.org') }}</h3>
    </div>
    <div class="card-body stack">
      <div
        v-for="c in CHANNELS"
        :key="c"
        class="row"
      >
        <label class="switch">
          <input
            v-model="enabled[c]"
            type="checkbox"
            :data-testid="`ch-enabled-${c}`"
          >
          <span />
        </label>
        <b style="min-width: 90px">{{ c === 'TELEGRAM' ? 'Telegram' : 'Zalo OA' }}</b>
        <input
          v-model="secrets[c]"
          class="input"
          type="password"
          autocomplete="off"
          style="max-width: 320px"
          :placeholder="configs[c]?.hasSecret ? t('channels.secretKept') : t(`channels.secret${c}`)"
          :data-testid="`ch-secret-${c}`"
        >
        <button
          class="btn btn-sm"
          :data-testid="`ch-save-${c}`"
          @click="save(c)"
        >
          {{ t('common.save') }}
        </button>
      </div>
      <p class="small muted">
        {{ t('channels.orgHint') }}
      </p>
    </div>
  </section>
</template>
