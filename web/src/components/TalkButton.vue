<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import AppIcon from './AppIcon.vue'
import { talkApi } from '@/services/api'
import { PcmResampler } from '@/player/PcmResampler'
import { useToastStore } from '@/stores/toast'

/**
 * Nhấn giữ để nói (US-15, design/live.html "Đàm thoại"): nhấn chuột/chạm hoặc giữ phím Space trên nút. Core kiểm tra
 * TALK_AUDIO và mở kênh tới loa camera; âm thanh micro được hạ về PCM 8 kHz và gửi qua WebSocket tới Device Gateway
 * (Gateway mã hóa G.711 gửi camera). Nhả nút thì kết thúc, Core ghi nhật ký TALK_AUDIO kèm thời lượng.
 */
const props = defineProps<{ cameraId: string }>()
const { t } = useI18n()
const toast = useToastStore()

type State = 'idle' | 'connecting' | 'talking'
const state = ref<State>('idle')
const seconds = ref(0)
const last = ref<number | null>(null)

let held = false
// press() đang chạy (xin micro, mở phiên): release() để press() tự dọn khi xong
let pressing = false
let sessionId: string | null = null
let ws: WebSocket | null = null
let mic: MediaStream | null = null
let ctx: AudioContext | null = null
let timer: number | undefined

const label = computed(() => {
  if (state.value === 'connecting') return t('talk.connecting')
  if (state.value === 'talking') return t('talk.talking', { s: seconds.value })
  return t('talk.hold')
})

function openSocket(url: string): Promise<WebSocket> {
  const scheme = location.protocol === 'https:' ? 'wss' : 'ws'
  const socket = new WebSocket(`${scheme}://${location.host}${url}`)
  socket.binaryType = 'arraybuffer'
  return new Promise((resolve, reject) => {
    socket.onopen = () => resolve(socket)
    socket.onerror = () => reject(new Error(t('talk.socketFailed')))
  })
}

async function press() {
  if (state.value !== 'idle') return
  held = true
  pressing = true
  state.value = 'connecting'
  try {
    // allSettled: một bên lỗi thì vẫn giữ được bên kia để dọn (tắt micro, đóng phiên đã mở)
    const [micResult, sessionResult] = await Promise.allSettled([
      navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true, channelCount: 1 } }),
      talkApi.start(props.cameraId),
    ])
    if (micResult.status === 'fulfilled') mic = micResult.value
    if (sessionResult.status === 'fulfilled') sessionId = sessionResult.value.sessionId
    if (micResult.status === 'rejected') throw micResult.reason
    if (sessionResult.status === 'rejected') throw sessionResult.reason
    const session = sessionResult.value
    ws = await openSocket(session.wsUrl)
    ws.onclose = (e) => {
      if (state.value === 'talking' && e.code !== 1000) toast.push('danger', t('talk.lost'))
      void release()
    }
    if (!held) return
    ctx = new AudioContext()
    const source = ctx.createMediaStreamSource(micResult.value)
    // ScriptProcessor (không cần tệp worklet riêng): khối 2048 mẫu ≈ 43 ms ở 48 kHz
    const processor = ctx.createScriptProcessor(2048, 1, 1)
    const resampler = new PcmResampler(ctx.sampleRate, session.sampleRate)
    processor.onaudioprocess = (e) => {
      const pcm = resampler.push(e.inputBuffer.getChannelData(0))
      if (pcm.length > 0 && ws?.readyState === WebSocket.OPEN) ws.send(pcm.buffer)
    }
    source.connect(processor)
    processor.connect(ctx.destination)
    state.value = 'talking'
    seconds.value = 0
    timer = window.setInterval(() => seconds.value++, 1000)
  } catch (e) {
    // Nhả nút ngay khi đang kết nối thì phiên đã đóng: không báo lỗi
    if (held) {
      if (e instanceof DOMException && e.name === 'NotAllowedError') toast.push('danger', t('talk.micDenied'))
      else toast.error(e)
    }
  } finally {
    pressing = false
    // Đã nhả nút trong lúc kết nối, hoặc lỗi: dọn tại đây (release() không dọn khi press() còn chạy)
    if (!held || state.value !== 'talking') await release()
  }
}

/** Nhả nút (hoặc lỗi): dừng micro, đóng WebSocket, báo Core kết thúc để ghi nhật ký. */
async function release() {
  held = false
  if (pressing || state.value === 'idle') return
  window.clearInterval(timer)
  mic?.getTracks().forEach((track) => track.stop())
  void ctx?.close()
  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send('end')
    ws.close(1000)
  }
  const id = sessionId
  mic = null
  ctx = null
  ws = null
  sessionId = null
  state.value = 'idle'
  if (id) {
    try {
      last.value = (await talkApi.end(props.cameraId, id)).durationSeconds
    } catch {
      // Phiên đã được Core ghi nhật ký khi quét định kỳ
    }
  }
}

function key(e: KeyboardEvent, down: boolean) {
  if (e.code !== 'Space' && e.code !== 'Enter') return
  e.preventDefault()
  if (down && !e.repeat) void press()
  else if (!down) void release()
}

watch(() => props.cameraId, () => { void release(); last.value = null })
onBeforeUnmount(() => void release())
</script>

<template>
  <div
    class="stack talk"
    data-testid="talk-panel"
  >
    <h3>{{ t('talk.title') }}</h3>
    <button
      class="btn talk-btn"
      :class="{ 'btn-primary': state === 'talking' }"
      :data-state="state"
      data-testid="talk-button"
      :aria-pressed="state === 'talking'"
      @pointerdown.prevent="press"
      @pointerup="release"
      @pointerleave="release"
      @pointercancel="release"
      @keydown="key($event, true)"
      @keyup="key($event, false)"
    >
      <AppIcon name="mic" />{{ label }}
    </button>
    <p
      v-if="last !== null && state === 'idle'"
      class="small muted"
      data-testid="talk-last"
    >
      {{ t('talk.last', { s: last.toFixed(1) }) }}
    </p>
  </div>
</template>

<style scoped>
.talk-btn {
  justify-content: center;
  touch-action: none;
  user-select: none;
}
</style>
