<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { play } from '@/player/createPlayer'
import type { StreamPlayer } from '@/player/types'

/** Khung hình xem trước khi kiểm tra kết nối (US-02 AC1): WebRTC, lỗi thì HLS. */
const props = defineProps<{ whepUrl: string; hlsUrl: string; token: string }>()
const { t } = useI18n()
const video = ref<HTMLVideoElement | null>(null)
const state = ref<'loading' | 'playing' | 'error'>('loading')
let player: StreamPlayer | null = null

onMounted(async () => {
  if (!video.value) return
  try {
    player = (await play(video.value, { whepUrl: props.whepUrl, hlsUrl: props.hlsUrl, token: props.token }, 'auto')).player
    state.value = 'playing'
  } catch {
    state.value = 'error'
  }
})
onBeforeUnmount(() => void player?.stop())
</script>

<template>
  <div
    class="tile"
    style="aspect-ratio: 16 / 9"
  >
    <video
      ref="video"
      class="scene"
      style="width: 100%; height: 100%; object-fit: contain; background: var(--video-bg)"
      muted
      playsinline
      autoplay
      data-testid="preview-video"
    />
    <div
      v-if="state !== 'playing'"
      class="state"
    >
      {{ state === 'loading' ? t('poc.connecting') : t('addCamera.previewFailed') }}
    </div>
  </div>
</template>
