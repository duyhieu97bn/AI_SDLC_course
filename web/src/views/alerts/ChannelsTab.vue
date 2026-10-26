<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { channelApi, type ChannelBinding } from '@/services/api'
import { useToastStore } from '@/stores/toast'

/**
 * Kênh nhận của tôi (US-36.5): liên kết chat ID Telegram / user ID Zalo để nhận cảnh báo. Web luôn bật, email theo địa
 * chỉ tài khoản.
 */
const { t } = useI18n()
const toast = useToastStore()
const CHANNELS = ['TELEGRAM', 'ZALO'] as const
const values = ref<Record<string, string>>({ TELEGRAM: '', ZALO: '' })
const bound = ref<ChannelBinding[]>([])

async function load() {
  try {
    bound.value = await channelApi.mine()
    for (const c of CHANNELS) values.value[c] = bound.value.find((b) => b.channel === c)?.address ?? ''
  } catch (e) {
    toast.error(e)
  }
}

async function save(channel: string) {
  try {
    if (values.value[channel]?.trim()) await channelApi.bind(channel, values.value[channel]!.trim())
    else await channelApi.unbind(channel)
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
    data-testid="my-channels"
  >
    <div class="card-head">
      <h3>{{ t('channels.mine') }}</h3>
    </div>
    <div class="card-body stack">
      <p class="small muted">
        {{ t('channels.mineHint') }}
      </p>
      <div
        v-for="c in CHANNELS"
        :key="c"
        class="row"
      >
        <b style="min-width: 90px">{{ c === 'TELEGRAM' ? 'Telegram' : 'Zalo' }}</b>
        <input
          v-model="values[c]"
          class="input"
          style="max-width: 280px"
          :placeholder="t(`channels.placeholder${c}`)"
          :data-testid="`my-${c}`"
        >
        <button
          class="btn btn-sm"
          :data-testid="`save-my-${c}`"
          @click="save(c)"
        >
          {{ t('common.save') }}
        </button>
        <span
          v-if="bound.some((b) => b.channel === c)"
          class="badge badge-ok"
        >{{ t('channels.linked') }}</span>
      </div>
    </div>
  </section>
</template>
