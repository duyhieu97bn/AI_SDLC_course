<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import AppIcon from '@/components/AppIcon.vue'
import AppModal from '@/components/AppModal.vue'
import { alertWorkflowApi, type AlertDetail, type AlertOutcome, type Person } from '@/services/api'
import { blobUrl } from '@/services/http'
import { useAuthStore } from '@/stores/auth'
import { useToastStore } from '@/stores/toast'

/**
 * Chi tiết cảnh báo (US-37, US-38, design/alerts.html): ảnh sự kiện, nhận xử lý, chuyển giao kèm lý do, đóng bắt buộc
 * chọn kết quả, lịch sử trạng thái và chuyển giao; "Xem lại" mở video từ thời điểm cảnh báo trừ 10 giây.
 */
const props = defineProps<{ alertId: string }>()
const emit = defineEmits<{ changed: []; close: [] }>()
const { t } = useI18n()
const auth = useAuthStore()
const toast = useToastStore()
const detail = ref<AlertDetail | null>(null)
const image = ref<string | null>(null)
const modal = ref<'reassign' | 'close' | null>(null)
const people = ref<Person[]>([])
const form = ref({ userId: '', reason: '', outcome: '' as AlertOutcome | '', note: '' })
const OUTCOMES: AlertOutcome[] = ['RESOLVED', 'FALSE_ALARM', 'ESCALATED', 'NO_ACTION_NEEDED']

const a = computed(() => detail.value?.alert ?? null)
const mine = computed(() => !!a.value && a.value.assigneeId === auth.user?.id)
/** US-38: phát từ thời điểm cảnh báo trừ 10 giây */
const playbackLink = computed(() =>
  a.value?.cameraId
    ? { path: '/playback', query: { camera: a.value.cameraId, at: new Date(new Date(a.value.occurredAt).getTime() - 10_000).toISOString() } }
    : null,
)

async function load() {
  try {
    detail.value = await alertWorkflowApi.detail(props.alertId)
    if (image.value) URL.revokeObjectURL(image.value)
    image.value = null
    if (detail.value.alert.snapshotStatus === 'READY') {
      image.value = await blobUrl(alertWorkflowApi.snapshotUrl(props.alertId)).catch(() => null)
    }
  } catch (e) {
    toast.error(e)
  }
}

async function act(fn: () => Promise<AlertDetail>) {
  try {
    detail.value = await fn()
    modal.value = null
    emit('changed')
  } catch (e) {
    toast.error(e)
  }
}

async function openReassign() {
  people.value = await alertWorkflowApi.assignees(props.alertId).catch(() => [])
  form.value = { ...form.value, userId: people.value[0]?.id ?? '', reason: '' }
  modal.value = 'reassign'
}

function openClose() {
  form.value = { ...form.value, outcome: '', note: '' }
  modal.value = 'close'
}

watch(() => props.alertId, load, { immediate: true })
onBeforeUnmount(() => image.value && URL.revokeObjectURL(image.value))
</script>

<template>
  <section
    v-if="a && detail"
    class="card"
    data-testid="alert-detail"
  >
    <div class="card-head">
      <h3 class="wrap">
        {{ a.title }}
      </h3>
      <span class="spacer" />
      <button
        class="icon-btn"
        :aria-label="t('common.close')"
        @click="emit('close')"
      >
        <AppIcon name="x" />
      </button>
    </div>
    <div class="card-body stack">
      <div class="row small">
        <span :class="['badge', a.status === 'NEW' ? 'badge-danger' : a.status === 'IN_PROGRESS' ? 'badge-warn' : a.status === 'RECOVERED' ? 'badge-ok' : 'badge-muted']">
          {{ t(`alerts.status${a.status}`) }}
        </span>
        <span class="muted">{{ new Date(a.occurredAt).toLocaleString() }}</span>
        <span
          v-if="detail.assignee"
          data-testid="alert-assignee"
        >{{ t('alerts.assignee') }}: <b>{{ detail.assignee.name }}</b></span>
      </div>
      <img
        v-if="image"
        :src="image"
        :alt="t('alerts.snapshot')"
        class="snapshot"
        data-testid="alert-snapshot"
      >
      <p
        v-else-if="a.snapshotStatus === 'PENDING'"
        class="small muted"
      >
        {{ t('alerts.snapshotPending') }}
      </p>
      <div class="row">
        <RouterLink
          v-if="playbackLink"
          class="btn btn-sm"
          :to="playbackLink"
          data-testid="alert-playback"
        >
          <AppIcon
            name="history"
            small
          />{{ t('alerts.playback') }}
        </RouterLink>
        <RouterLink
          v-if="a.cameraId"
          class="btn btn-sm"
          :to="{ path: '/live', query: { camera: a.cameraId } }"
          data-testid="alert-live"
        >
          <AppIcon
            name="video"
            small
          />{{ t('alerts.live') }}
        </RouterLink>
        <span class="spacer" />
        <button
          v-if="a.status === 'NEW'"
          class="btn btn-sm btn-primary"
          data-testid="alert-ack"
          @click="act(() => alertWorkflowApi.acknowledge(a!.id))"
        >
          {{ t('alerts.acknowledge') }}
        </button>
        <button
          v-if="a.status === 'IN_PROGRESS' && mine"
          class="btn btn-sm"
          data-testid="alert-reassign"
          @click="openReassign"
        >
          <AppIcon
            name="send"
            small
          />{{ t('alerts.reassign') }}
        </button>
        <button
          v-if="a.status !== 'CLOSED' && (a.status !== 'IN_PROGRESS' || mine || auth.can('MANAGE_SYSTEM'))"
          class="btn btn-sm"
          data-testid="alert-close"
          @click="openClose"
        >
          <AppIcon
            name="check"
            small
          />{{ t('alerts.closeAlert') }}
        </button>
      </div>
      <p
        v-if="a.outcome"
        class="small"
      >
        {{ t('alerts.outcome') }}: <b>{{ t(`alerts.outcome${a.outcome}`) }}</b><template v-if="a.outcomeNote">
          – {{ a.outcomeNote }}
        </template>
      </p>
      <div>
        <b class="small">{{ t('alerts.history') }}</b>
        <ul
          class="small"
          data-testid="alert-history"
        >
          <li
            v-for="(h, i) in detail.history"
            :key="i"
          >
            {{ new Date(h.at).toLocaleString() }} · {{ h.from ? t(`alerts.status${h.from}`) + ' → ' : '' }}{{ t(`alerts.status${h.to}`) }}
            <span
              v-if="h.note"
              class="muted"
            >– {{ h.note }}</span>
          </li>
          <li
            v-for="(h, i) in detail.handovers"
            :key="`h${i}`"
          >
            {{ new Date(h.at).toLocaleString() }} · {{ t('alerts.handover', { from: h.from.name, to: h.to.name }) }}
            <span class="muted">– {{ h.reason }}</span>
          </li>
        </ul>
      </div>
    </div>
  </section>

  <AppModal
    v-if="modal === 'reassign'"
    :title="t('alerts.reassign')"
    size="sm"
    @close="modal = null"
  >
    <div class="field">
      <label for="ra-user">{{ t('alerts.reassignTo') }}</label>
      <select
        id="ra-user"
        v-model="form.userId"
        class="select"
        data-testid="reassign-user"
      >
        <option
          v-for="p in people"
          :key="p.id"
          :value="p.id"
        >
          {{ p.name }}
        </option>
      </select>
    </div>
    <div class="field">
      <label for="ra-reason">{{ t('alerts.reason') }}</label>
      <textarea
        id="ra-reason"
        v-model="form.reason"
        class="input"
        rows="3"
        maxlength="1000"
        data-testid="reassign-reason"
      />
    </div>
    <template #footer>
      <button
        class="btn"
        @click="modal = null"
      >
        {{ t('common.cancel') }}
      </button>
      <button
        class="btn btn-primary"
        :disabled="!form.userId || !form.reason.trim()"
        data-testid="reassign-submit"
        @click="act(() => alertWorkflowApi.reassign(a!.id, form.userId, form.reason))"
      >
        {{ t('alerts.reassign') }}
      </button>
    </template>
  </AppModal>

  <AppModal
    v-if="modal === 'close'"
    :title="t('alerts.closeAlert')"
    size="sm"
    @close="modal = null"
  >
    <div class="field">
      <label for="cl-outcome">{{ t('alerts.outcome') }}</label>
      <select
        id="cl-outcome"
        v-model="form.outcome"
        class="select"
        data-testid="close-outcome"
      >
        <option value="">
          {{ t('alerts.chooseOutcome') }}
        </option>
        <option
          v-for="o in OUTCOMES"
          :key="o"
          :value="o"
        >
          {{ t(`alerts.outcome${o}`) }}
        </option>
      </select>
    </div>
    <div class="field">
      <label for="cl-note">{{ t('alerts.note') }}</label>
      <textarea
        id="cl-note"
        v-model="form.note"
        class="input"
        rows="3"
        maxlength="1000"
      />
    </div>
    <template #footer>
      <button
        class="btn"
        @click="modal = null"
      >
        {{ t('common.cancel') }}
      </button>
      <!-- AC3: nút vẫn bấm được để server báo lỗi rõ ràng khi chưa chọn kết quả -->
      <button
        class="btn btn-primary"
        data-testid="close-submit"
        @click="act(() => alertWorkflowApi.close(a!.id, form.outcome, form.note))"
      >
        {{ t('alerts.closeAlert') }}
      </button>
    </template>
  </AppModal>
</template>

<style scoped>
.snapshot {
  max-width: 100%;
  max-height: 320px;
  border-radius: 8px;
  object-fit: contain;
  background: #000;
}
</style>
