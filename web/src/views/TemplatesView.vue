<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import AppIcon from '@/components/AppIcon.vue'
import AppModal from '@/components/AppModal.vue'
import { cameraApi, templateApi, type Camera, type ConfigTemplate, type TemplateApplication } from '@/services/api'
import { useToastStore } from '@/stores/toast'

/**
 * Cấu hình mẫu (US-27, design/templates.html): mẫu gồm chính sách ghi, luồng chính, hình ảnh; áp cho nhiều camera và xem
 * kết quả từng camera (Thành công / Một phần – bỏ qua tham số không hỗ trợ / Thất bại) kèm nút "Áp lại".
 */
const { t } = useI18n()
const toast = useToastStore()
const templates = ref<ConfigTemplate[]>([])
const cameras = ref<Camera[]>([])
const editing = ref<ConfigTemplate | null>(null)
const applying = ref<ConfigTemplate | null>(null)
const chosen = ref<Set<string>>(new Set())
const filter = ref('')
const result = ref<TemplateApplication | null>(null)
const busy = ref(false)
const sections = ref({ recording: true, stream: false, image: false })

const visibleCameras = computed(() => cameras.value.filter((c) => c.name.toLowerCase().includes(filter.value.toLowerCase())))
const BADGE: Record<string, string> = { SUCCESS: 'badge-ok', PARTIAL: 'badge-warn', FAILED: 'badge-danger' }

async function load() {
  try {
    ;[templates.value, cameras.value] = await Promise.all([templateApi.list(), cameraApi.list({ size: 1000 }).then((p) => p.items)])
  } catch (e) {
    toast.error(e)
  }
}

function edit(tpl?: ConfigTemplate) {
  const base: ConfigTemplate = tpl ? JSON.parse(JSON.stringify(tpl)) : { name: '', description: '', recording: null, stream: null, image: null }
  sections.value = { recording: !!base.recording || !tpl, stream: !!base.stream, image: !!base.image }
  base.recording ??= { mode: 'EVENT', schedule: [], preEventSeconds: 10, postEventSeconds: 30 }
  base.stream ??= { codec: 'H264', width: null, height: null, fps: 15, bitrateKbps: 2048, govLength: null }
  base.image ??= { brightness: null, contrast: null, irMode: 'AUTO', wdr: null }
  editing.value = base
}

async function save() {
  const e = editing.value
  if (!e) return
  try {
    await templateApi.save({
      ...e,
      recording: sections.value.recording ? e.recording : null,
      stream: sections.value.stream ? e.stream : null,
      image: sections.value.image ? e.image : null,
    })
    editing.value = null
    toast.ok(t('common.saved'))
    await load()
  } catch (err) {
    toast.error(err)
  }
}

async function remove(tpl: ConfigTemplate) {
  if (!tpl.id || !confirm(t('templates.confirmDelete', { name: tpl.name }))) return
  await templateApi.remove(tpl.id).catch((e) => toast.error(e))
  await load()
}

function openApply(tpl: ConfigTemplate) {
  applying.value = tpl
  chosen.value = new Set()
  result.value = null
}

function toggleCamera(id: string) {
  const s = new Set(chosen.value)
  if (s.has(id)) s.delete(id)
  else s.add(id)
  chosen.value = s
}

async function apply() {
  if (!applying.value?.id) return
  busy.value = true
  try {
    result.value = await templateApi.apply(applying.value.id, [...chosen.value])
  } catch (e) {
    toast.error(e)
  } finally {
    busy.value = false
  }
}

async function reapply() {
  if (!result.value) return
  busy.value = true
  try {
    result.value = await templateApi.reapply(result.value.id)
  } catch (e) {
    toast.error(e)
  } finally {
    busy.value = false
  }
}

function detailText(d: TemplateApplication['results'][number]['details'][number]) {
  return `${t(`templates.section${d.section}`)}${d.parameter ? ' · ' + d.parameter : ''}: ${d.status === 'OK' ? 'OK' : d.message ?? d.status}`
}

onMounted(load)
</script>

<template>
  <section class="card">
    <div class="card-head">
      <h3>{{ t('templates.title') }}</h3>
      <span class="spacer" />
      <button
        class="btn btn-sm btn-primary"
        data-testid="template-new"
        @click="edit()"
      >
        <AppIcon
          name="plus"
          small
        />{{ t('templates.new') }}
      </button>
    </div>
    <div class="table-wrap">
      <table class="table">
        <tbody>
          <tr v-if="templates.length === 0">
            <td class="muted">
              {{ t('templates.empty') }}
            </td>
          </tr>
          <tr
            v-for="tpl in templates"
            :key="tpl.id"
            data-testid="template-row"
          >
            <td>
              <b>{{ tpl.name }}</b>
              <div class="small muted">
                {{ [tpl.recording ? t('templates.sectionRECORDING') : '', tpl.stream ? t('templates.sectionSTREAM') : '', tpl.image ? t('templates.sectionIMAGE') : ''].filter(Boolean).join(' · ') }}
              </div>
            </td>
            <td class="row">
              <button
                class="btn btn-sm btn-primary"
                :data-testid="`template-apply-${tpl.name}`"
                @click="openApply(tpl)"
              >
                {{ t('templates.apply') }}
              </button>
              <button
                class="btn btn-sm"
                @click="edit(tpl)"
              >
                {{ t('common.edit') }}
              </button>
              <button
                class="btn btn-sm btn-ghost"
                @click="remove(tpl)"
              >
                {{ t('common.delete') }}
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>

  <AppModal
    v-if="editing"
    :title="editing.id ? t('common.edit') : t('templates.new')"
    @close="editing = null"
  >
    <div class="field">
      <label for="tpl-name">{{ t('templates.name') }}</label>
      <input
        id="tpl-name"
        v-model="editing.name"
        class="input"
        maxlength="200"
        data-testid="template-name"
      >
    </div>
    <label class="row"><input
      v-model="sections.recording"
      type="checkbox"
      data-testid="tpl-sec-recording"
    ><b>{{ t('templates.sectionRECORDING') }}</b></label>
    <div
      v-if="sections.recording && editing.recording"
      class="row"
    >
      <select
        v-model="editing.recording.mode"
        class="select"
        style="width: auto"
        data-testid="tpl-mode"
      >
        <option
          v-for="m in ['CONTINUOUS', 'EVENT', 'OFF']"
          :key="m"
          :value="m"
        >
          {{ t(`recording.mode${m}`) }}
        </option>
      </select>
    </div>
    <label class="row"><input
      v-model="sections.stream"
      type="checkbox"
      data-testid="tpl-sec-stream"
    ><b>{{ t('templates.sectionSTREAM') }}</b></label>
    <div
      v-if="sections.stream && editing.stream"
      class="row"
    >
      <label class="small">FPS <input
        v-model.number="editing.stream.fps"
        class="input"
        type="number"
        min="1"
        max="60"
        style="width: 80px"
        data-testid="tpl-fps"
      ></label>
      <label class="small">Kbps <input
        v-model.number="editing.stream.bitrateKbps"
        class="input"
        type="number"
        min="64"
        style="width: 100px"
      ></label>
    </div>
    <label class="row"><input
      v-model="sections.image"
      type="checkbox"
      data-testid="tpl-sec-image"
    ><b>{{ t('templates.sectionIMAGE') }}</b></label>
    <div
      v-if="sections.image && editing.image"
      class="row"
    >
      <select
        v-model="editing.image.irMode"
        class="select"
        style="width: auto"
      >
        <option
          v-for="m in ['AUTO', 'DAY', 'NIGHT']"
          :key="m"
          :value="m"
        >
          {{ t(`image.ir${m}`) }}
        </option>
      </select>
      <label class="row small"><input
        type="checkbox"
        :checked="!!editing.image.wdr"
        data-testid="tpl-wdr"
        @change="editing.image.wdr = ($event.target as HTMLInputElement).checked || null"
      >WDR</label>
    </div>
    <template #footer>
      <button
        class="btn"
        @click="editing = null"
      >
        {{ t('common.cancel') }}
      </button>
      <button
        class="btn btn-primary"
        :disabled="!editing.name.trim() || (!sections.recording && !sections.stream && !sections.image)"
        data-testid="template-save"
        @click="save"
      >
        {{ t('common.save') }}
      </button>
    </template>
  </AppModal>

  <AppModal
    v-if="applying"
    :title="t('templates.applyTitle', { name: applying.name })"
    @close="applying = null"
  >
    <template v-if="!result">
      <input
        v-model="filter"
        class="input"
        :placeholder="t('cameras.search')"
        data-testid="apply-filter"
      >
      <div
        class="stack"
        style="max-height: 300px; overflow-y: auto; margin-top: 8px"
      >
        <label
          v-for="c in visibleCameras"
          :key="c.id"
          class="row small"
        >
          <input
            type="checkbox"
            :checked="chosen.has(c.id)"
            :data-testid="`apply-cam-${c.name}`"
            @change="toggleCamera(c.id)"
          >{{ c.name }}
        </label>
      </div>
    </template>
    <div
      v-else
      class="stack"
      data-testid="apply-results"
    >
      <div
        v-for="r in result.results"
        :key="r.cameraId"
        data-testid="apply-result-row"
      >
        <div class="row">
          <span :class="['badge', BADGE[r.result]]">{{ t(`templates.result${r.result}`) }}</span>
          <b>{{ r.cameraName }}</b>
        </div>
        <ul class="small muted">
          <li
            v-for="(d, i) in r.details.filter((x) => x.status !== 'OK')"
            :key="i"
          >
            {{ detailText(d) }}
          </li>
        </ul>
      </div>
    </div>
    <template #footer>
      <button
        class="btn"
        @click="applying = null"
      >
        {{ t('common.close') }}
      </button>
      <button
        v-if="result && result.results.some((r) => r.result === 'FAILED')"
        class="btn"
        :disabled="busy"
        data-testid="apply-retry"
        @click="reapply"
      >
        {{ t('templates.retry') }}
      </button>
      <button
        v-if="!result"
        class="btn btn-primary"
        :disabled="busy || chosen.size === 0"
        data-testid="apply-submit"
        @click="apply"
      >
        {{ t('templates.applyTo', { n: chosen.size }) }}
      </button>
    </template>
  </AppModal>
</template>
