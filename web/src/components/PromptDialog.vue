<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import AppModal from './AppModal.vue'

/** Hộp thoại nhập một giá trị (tên địa điểm, khu vực, nhóm...). */
const props = defineProps<{ title: string; label: string; initial?: string }>()
const emit = defineEmits<{ close: []; submit: [value: string] }>()
const { t } = useI18n()
const value = ref(props.initial ?? '')
</script>

<template>
  <AppModal
    :title="title"
    size="sm"
    @close="emit('close')"
  >
    <form
      class="field"
      @submit.prevent="value.trim() && emit('submit', value.trim())"
    >
      <label for="prompt-value">{{ label }}</label>
      <input
        id="prompt-value"
        v-model="value"
        class="input"
        maxlength="200"
        autofocus
      >
    </form>
    <template #footer>
      <button
        class="btn"
        @click="emit('close')"
      >
        {{ t('common.cancel') }}
      </button>
      <button
        class="btn btn-primary"
        :disabled="!value.trim()"
        data-testid="prompt-submit"
        @click="emit('submit', value.trim())"
      >
        {{ t('common.save') }}
      </button>
    </template>
  </AppModal>
</template>
