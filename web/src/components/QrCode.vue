<script setup lang="ts">
import { ref, watchEffect } from 'vue'
import QRCode from 'qrcode'

/** Mã QR sinh hoàn toàn trên trình duyệt (secret 2FA không gửi tới dịch vụ ngoài). */
const props = defineProps<{ value: string; size?: number }>()
const src = ref('')

watchEffect(async () => {
  src.value = props.value ? await QRCode.toDataURL(props.value, { margin: 1, width: props.size ?? 180 }) : ''
})
</script>

<template>
  <img
    v-if="src"
    :src="src"
    :width="size ?? 180"
    :height="size ?? 180"
    alt="QR"
    style="background: #fff; border-radius: 8px; padding: 6px"
  >
</template>
