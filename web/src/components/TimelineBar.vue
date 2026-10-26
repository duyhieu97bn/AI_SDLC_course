<script setup lang="ts">
import { computed } from 'vue'
import type { Timeline } from '@/services/api'

/**
 * Dòng thời gian (US-17 AC3, design/playback.html): đoạn có ghi, mốc sự kiện, khoảng giữ lại bằng màu khác nhau;
 * khoảng trống để nền. Thu phóng từ 24 giờ tới 10 phút quanh con trỏ; nhấp để chọn thời điểm.
 */
const props = defineProps<{ data: Timeline | null; from: Date; to: Date; cursor: Date | null }>()
const emit = defineEmits<{ seek: [at: Date] }>()

const span = computed(() => props.to.getTime() - props.from.getTime())
function pct(t: string | Date) {
  const v = ((new Date(t).getTime() - props.from.getTime()) / span.value) * 100
  return Math.max(0, Math.min(100, v))
}
const ticks = computed(() => {
  const n = 6
  return Array.from({ length: n + 1 }, (_, i) => new Date(props.from.getTime() + (span.value * i) / n))
})
function label(d: Date) {
  return d.toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit' })
}
function onClick(e: MouseEvent) {
  const el = e.currentTarget as HTMLElement
  const r = el.getBoundingClientRect()
  const ratio = Math.max(0, Math.min(1, (e.clientX - r.left) / r.width))
  emit('seek', new Date(props.from.getTime() + span.value * ratio))
}
</script>

<template>
  <div
    class="timeline"
    role="slider"
    tabindex="0"
    :aria-valuemin="from.getTime()"
    :aria-valuemax="to.getTime()"
    :aria-valuenow="cursor?.getTime()"
    data-testid="timeline"
    @click="onClick"
  >
    <div class="track">
      <div
        v-for="(r, i) in data?.recordings ?? []"
        :key="`r${i}`"
        class="seg"
        data-testid="timeline-seg"
        :style="{ left: `${pct(r.start)}%`, width: `${Math.max(0.2, pct(r.end) - pct(r.start))}%` }"
      />
      <div
        v-for="(h, i) in data?.holds ?? []"
        :key="`h${i}`"
        class="sel"
        :title="h.reason"
        :style="{ left: `${pct(h.start)}%`, width: `${Math.max(0.2, pct(h.end) - pct(h.start))}%` }"
      />
      <div
        v-for="(ev, i) in data?.events ?? []"
        :key="`e${i}`"
        class="evt"
        :style="{ left: `${pct(ev)}%`, width: '3px' }"
      />
    </div>
    <div
      v-if="cursor && cursor >= from && cursor <= to"
      class="cursor"
      :data-time="label(cursor)"
      :style="{ left: `${pct(cursor)}%` }"
    />
    <div class="ticks">
      <span
        v-for="(t, i) in ticks"
        :key="i"
      >{{ label(t) }}</span>
    </div>
  </div>
</template>
