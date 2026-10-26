<script setup lang="ts">
import { computed } from 'vue'
import { ICONS, type IconName } from './icons'

const props = withDefaults(defineProps<{ name: IconName; small?: boolean }>(), { small: false })
// Nội dung SVG là hằng số trong mã nguồn (icons.ts), không lấy từ dữ liệu người dùng
const body = computed(() => ICONS[props.name])
</script>

<template>
  <!-- eslint-disable vue/no-v-html -- nội dung là hằng số trong icons.ts -->
  <svg
    :class="['icon', { 'icon-sm': small }]"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="2"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
    v-html="body"
  />
</template>
