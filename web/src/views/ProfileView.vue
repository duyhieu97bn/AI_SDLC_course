<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import QrCode from '@/components/QrCode.vue'
import RecoveryCodes from '@/components/RecoveryCodes.vue'
import { twoFactorApi } from '@/services/api'
import { useAuthStore } from '@/stores/auth'
import { useToastStore } from '@/stores/toast'

/** Hồ sơ cá nhân: ngôn ngữ (US-41) và xác thực hai lớp (US-31). */
const { t } = useI18n()
const auth = useAuthStore()
const toast = useToastStore()

const status = ref<{ enabled: boolean; required: boolean; remainingRecoveryCodes: number } | null>(null)
const setup = ref<{ secret: string; otpauthUri: string } | null>(null)
const code = ref('')
const codes = ref<string[]>([])

async function load() {
  status.value = await twoFactorApi.status()
}

async function run(action: () => Promise<void>) {
  try {
    await action()
    code.value = ''
    await load()
  } catch (e) {
    toast.error(e)
  }
}

const startSetup = () => run(async () => void (setup.value = await twoFactorApi.setup()))
const enable = () =>
  run(async () => {
    codes.value = (await twoFactorApi.enable(code.value)).recoveryCodes
    setup.value = null
  })
const regenerate = () => run(async () => void (codes.value = (await twoFactorApi.regenerate(code.value)).recoveryCodes))
const disable = () => run(async () => void (await twoFactorApi.disable(code.value)))

onMounted(() => void load().catch((e) => toast.error(e)))
</script>

<template>
  <section class="card">
    <div class="card-head">
      <h3>{{ t('profile.account') }}</h3>
    </div>
    <div class="card-body form-grid">
      <div class="field">
        <label>{{ t('users.displayName') }}</label>
        <span>{{ auth.user?.displayName }}</span>
      </div>
      <div class="field">
        <label>Email</label>
        <span>{{ auth.user?.email }}</span>
      </div>
      <div class="field">
        <label for="p-lang">{{ t('users.language') }}</label>
        <select
          id="p-lang"
          class="input"
          :value="auth.user?.language"
          @change="auth.updateLanguage(($event.target as HTMLSelectElement).value as 'vi' | 'en')"
        >
          <option value="vi">
            Tiếng Việt
          </option>
          <option value="en">
            English
          </option>
        </select>
      </div>
      <div class="field">
        <label>{{ t('users.roles') }}</label>
        <span>{{ auth.user?.roles.join(', ') }}</span>
      </div>
    </div>
  </section>

  <section
    v-if="status"
    class="card"
  >
    <div class="card-head">
      <h3>{{ t('twoFactor.title') }}</h3>
      <span class="spacer" />
      <span :class="['badge', status.enabled ? 'badge-ok' : 'badge-muted']">
        {{ status.enabled ? t('twoFactor.enabled') : t('twoFactor.disabled') }}
      </span>
      <span
        v-if="status.required"
        class="badge badge-warn no-dot"
      >{{ t('twoFactor.requiredByRole') }}</span>
    </div>
    <div class="card-body stack">
      <RecoveryCodes
        v-if="codes.length"
        :codes="codes"
      />
      <template v-if="!status.enabled">
        <p
          v-if="!setup"
          class="muted"
        >
          {{ t('twoFactor.explain') }}
        </p>
        <button
          v-if="!setup"
          class="btn btn-primary"
          style="align-self: flex-start"
          data-testid="2fa-start"
          @click="startSetup"
        >
          {{ t('twoFactor.enable') }}
        </button>
        <div
          v-else
          class="row"
          style="align-items: flex-start; gap: 16px"
        >
          <QrCode :value="setup.otpauthUri" />
          <div class="stack">
            <span class="small muted">{{ t('twoFactor.setupHint') }}</span>
            <span
              class="mono small"
              data-testid="totp-secret"
            >{{ setup.secret }}</span>
            <input
              v-model="code"
              class="input mono"
              maxlength="6"
              :placeholder="t('twoFactor.firstCode')"
              data-testid="2fa-code"
            >
            <button
              class="btn btn-primary"
              :disabled="code.length !== 6"
              data-testid="2fa-confirm"
              @click="enable"
            >
              {{ t('twoFactor.confirm') }}
            </button>
          </div>
        </div>
      </template>
      <template v-else>
        <p class="small muted">
          {{ t('twoFactor.remaining', { n: status.remainingRecoveryCodes }) }}
        </p>
        <div class="row">
          <input
            v-model="code"
            class="input mono"
            style="width: 160px"
            maxlength="9"
            :placeholder="t('twoFactor.code')"
          >
          <button
            class="btn"
            :disabled="code.length < 6"
            @click="regenerate"
          >
            {{ t('twoFactor.regenerate') }}
          </button>
          <button
            v-if="!status.required"
            class="btn btn-danger"
            :disabled="code.length < 6"
            @click="disable"
          >
            {{ t('twoFactor.disable') }}
          </button>
        </div>
      </template>
    </div>
  </section>
</template>
