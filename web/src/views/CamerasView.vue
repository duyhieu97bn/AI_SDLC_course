<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import AppIcon from '@/components/AppIcon.vue'
import CameraTree, { type TreeSelection } from '@/components/CameraTree.vue'
import MoveCameraDialog from '@/components/MoveCameraDialog.vue'
import PromptDialog from '@/components/PromptDialog.vue'
import AddCameraDialog from '@/components/AddCameraDialog.vue'
import DiscoveryWizard from '@/components/DiscoveryWizard.vue'
import AddRecorderDialog from '@/components/AddRecorderDialog.vue'
import ImportCamerasDialog from '@/components/ImportCamerasDialog.vue'
import ChangeCameraPasswordDialog from '@/components/ChangeCameraPasswordDialog.vue'
import { cameraApi, deviceApi, orgApi, type Camera, type CameraGroup, type SiteNode } from '@/services/api'
import { useAuthStore } from '@/stores/auth'
import { useToastStore } from '@/stores/toast'
import { useRealtimeStore } from '@/stores/realtime'

/** Danh sách camera theo cây Địa điểm/Khu vực/Nhóm, lọc nhanh, chuyển khu vực (US-05, design/cameras.html). */
const { t, d } = useI18n()
const auth = useAuthStore()
const toast = useToastStore()
const realtime = useRealtimeStore()
const addDialog = ref<'manual' | 'discovery' | 'recorder' | 'import' | null>(null)
const passwordFor = ref<Camera[] | null>(null)
const addMenu = ref(false)

/** Trạng thái hiện tại: ưu tiên sự kiện WebSocket mới hơn dữ liệu đã tải (US-04 AC1). */
function live(c: Camera): Camera {
  const e = realtime.cameraStatus[c.id]
  if (!e) return c
  const loaded = c.statusChangedAt ? new Date(c.statusChangedAt).getTime() : 0
  if (new Date(e.since).getTime() < loaded) return c
  return { ...c, status: e.status, statusChangedAt: e.since, lastOfflineAt: e.status === 'OFFLINE' ? e.since : c.lastOfflineAt }
}
const liveRows = computed(() => rows.value.map(live))

const sites = ref<SiteNode[]>([])
const groups = ref<CameraGroup[]>([])
const all = ref<Camera[]>([]) // mọi camera thấy được, để đếm trên cây
const rows = ref<Camera[]>([])
const total = ref(0)
const loading = ref(false)
const selection = ref<TreeSelection>({ kind: 'all' })
const filter = reactive({ q: '', status: '' })
const checked = ref<Set<string>>(new Set())
const dragIds = ref<string[]>([])
const moveDialog = ref<{ cameras: Camera[]; zoneId?: string } | null>(null)
const prompt = ref<{ title: string; label: string; initial?: string; onSubmit: (v: string) => Promise<void> } | null>(null)
const groupTarget = ref('')

const canManageSites = computed(() => auth.can('MANAGE_SYSTEM'))
const canConfigure = computed(() => auth.can('CONFIGURE_CAMERA'))

const siteName = computed(() => Object.fromEntries(sites.value.map((s) => [s.id, s.name])))
const zoneName = computed(() => Object.fromEntries(sites.value.flatMap((s) => s.zones.map((z) => [z.id, z.name]))))
const counts = computed(() => {
  const c = { zone: {} as Record<string, number>, site: {} as Record<string, number>, group: {} as Record<string, number>, total: all.value.length }
  for (const cam of all.value) {
    c.zone[cam.zoneId] = (c.zone[cam.zoneId] ?? 0) + 1
    c.site[cam.siteId] = (c.site[cam.siteId] ?? 0) + 1
    cam.groupIds.forEach((g) => (c.group[g] = (c.group[g] ?? 0) + 1))
  }
  return c
})
const selectedCameras = computed(() => rows.value.filter((r) => checked.value.has(r.id)))

async function loadTree() {
  const [s, g, a] = await Promise.all([orgApi.sites(), orgApi.groups(), cameraApi.list({ size: 1000 })])
  sites.value = s
  groups.value = g
  all.value = a.items
}

let seq = 0
async function loadRows() {
  const mine = ++seq
  loading.value = true
  try {
    const sel = selection.value
    const page = await cameraApi.list({
      siteId: sel.kind === 'site' ? sel.id : null,
      zoneId: sel.kind === 'zone' ? sel.id : null,
      groupId: sel.kind === 'group' ? sel.id : null,
      status: filter.status || null,
      q: filter.q || null,
      size: 500,
    })
    if (mine === seq) {
      rows.value = page.items
      total.value = page.total
    }
  } catch (e) {
    toast.error(e)
  } finally {
    if (mine === seq) loading.value = false
  }
}

async function reload() {
  try {
    await loadTree()
  } catch (e) {
    toast.error(e)
  }
  checked.value = new Set()
  await loadRows()
}

let debounce: ReturnType<typeof setTimeout> | undefined
watch(
  () => [filter.q, filter.status, selection.value],
  () => {
    clearTimeout(debounce)
    debounce = setTimeout(() => void loadRows(), 200)
  },
  { deep: true },
)

function toggle(id: string) {
  const s = new Set(checked.value)
  if (s.has(id)) s.delete(id)
  else s.add(id)
  checked.value = s
}
function toggleAll(on: boolean) {
  checked.value = new Set(on ? rows.value.map((r) => r.id) : [])
}

function onDragStart(c: Camera) {
  dragIds.value = checked.value.has(c.id) ? [...checked.value] : [c.id]
}
function onDropToZone(zoneId: string) {
  const cams = rows.value.filter((r) => dragIds.value.includes(r.id))
  if (cams.length) moveDialog.value = { cameras: cams, zoneId }
}

function ask(title: string, label: string, onSubmit: (v: string) => Promise<void>, initial?: string) {
  prompt.value = { title, label, initial, onSubmit }
}
async function runPrompt(v: string) {
  const p = prompt.value
  if (!p) return
  try {
    await p.onSubmit(v)
    prompt.value = null
    toast.ok(t('common.saved'))
    await reload()
  } catch (e) {
    toast.error(e)
  }
}
async function remove(action: () => Promise<unknown>) {
  if (!confirm(t('common.confirmDelete'))) return
  try {
    await action()
    await reload()
  } catch (e) {
    toast.error(e)
  }
}

async function renameCamera(c: Camera) {
  ask(t('common.rename'), t('cameras.camera'), async (v) => void (await deviceApi.rename(c.id, v)), c.name)
}

async function deleteCamera(c: Camera) {
  await remove(() => deviceApi.remove(c.id))
}

async function onAdded() {
  addDialog.value = null
  toast.ok(t('addCamera.saved'))
  await reload()
}

async function assignGroup() {
  if (!groupTarget.value) return
  try {
    await orgApi.setGroupMembers(groupTarget.value, [...checked.value], [])
    toast.ok(t('common.saved'))
    groupTarget.value = ''
    await reload()
  } catch (e) {
    toast.error(e)
  }
}

function statusBadge(s: Camera['status']) {
  return s === 'ONLINE' ? 'badge-ok' : s === 'OFFLINE' ? 'badge-danger' : 'badge-muted'
}

onMounted(reload)
</script>

<template>
  <div class="split">
    <CameraTree
      :sites="sites"
      :groups="groups"
      :selected="selection"
      :counts="counts"
      :can-manage-sites="canManageSites"
      :can-configure="canConfigure"
      @select="selection = $event"
      @add-site="ask(t('cameras.addSite'), t('cameras.siteName'), async (v) => void (await orgApi.createSite(v)))"
      @add-zone="(siteId) => ask(t('cameras.addZone'), t('cameras.zoneName'), async (v) => void (await orgApi.createZone(siteId, v)))"
      @rename-zone="(id, name) => ask(t('common.rename'), t('cameras.zoneName'), (v) => orgApi.renameZone(id, v).then(() => undefined), name)"
      @delete-zone="(id) => remove(() => orgApi.deleteZone(id))"
      @delete-site="(id) => remove(() => orgApi.deleteSite(id))"
      @add-group="ask(t('cameras.addGroup'), t('cameras.groupName'), async (v) => void (await orgApi.createGroup(v)))"
      @drop-cameras="onDropToZone"
    />
    <section class="stack">
      <div class="row">
        <label
          class="search"
          style="display: flex"
        >
          <AppIcon name="search" />
          <input
            v-model="filter.q"
            :placeholder="t('cameras.search')"
            :aria-label="t('cameras.search')"
            data-testid="camera-search"
          >
        </label>
        <select
          v-model="filter.status"
          class="input"
          style="width: auto"
          :aria-label="t('cameras.status')"
        >
          <option value="">
            {{ t('cameras.anyStatus') }}
          </option>
          <option value="ONLINE">
            {{ t('cameras.statusONLINE') }}
          </option>
          <option value="OFFLINE">
            {{ t('cameras.statusOFFLINE') }}
          </option>
          <option value="UNKNOWN">
            {{ t('cameras.statusUNKNOWN') }}
          </option>
        </select>
        <span class="spacer" />
        <div
          v-if="canConfigure"
          :class="['dropdown', { open: addMenu }]"
        >
          <button
            class="btn btn-primary"
            data-testid="add-camera"
            @click="addMenu = !addMenu"
          >
            <AppIcon
              name="plus"
              small
            />{{ t('addCamera.button') }}
            <AppIcon
              name="down"
              small
            />
          </button>
          <div class="dropdown-menu">
            <button
              data-testid="add-discovery"
              @click="addMenu = false; addDialog = 'discovery'"
            >
              <AppIcon name="search" /><span><b>{{ t('discovery.title') }}</b><br><span class="small muted">{{ t('discovery.menuHint') }}</span></span>
            </button>
            <button
              data-testid="add-manual"
              @click="addMenu = false; addDialog = 'manual'"
            >
              <AppIcon name="edit" /><span><b>{{ t('addCamera.title') }}</b><br><span class="small muted">{{ t('addCamera.menuHint') }}</span></span>
            </button>
            <button
              data-testid="add-import"
              @click="addMenu = false; addDialog = 'import'"
            >
              <AppIcon name="upload" /><span><b>{{ t('import.title') }}</b><br><span class="small muted">{{ t('import.menuHint') }}</span></span>
            </button>
            <button
              data-testid="add-recorder"
              @click="addMenu = false; addDialog = 'recorder'"
            >
              <AppIcon name="server" /><span><b>{{ t('recorder.title') }}</b><br><span class="small muted">{{ t('recorder.menuHint') }}</span></span>
            </button>
          </div>
        </div>
      </div>

      <div
        v-if="checked.size > 0 && canConfigure"
        class="bulkbar row"
      >
        <b>{{ t('cameras.selected', { n: checked.size }) }}</b>
        <span class="spacer" />
        <button
          class="btn btn-sm"
          data-testid="bulk-password"
          @click="passwordFor = selectedCameras"
        >
          <AppIcon
            name="key"
            small
          />{{ t('maintenance.changePassword') }}
        </button>
        <button
          class="btn btn-sm"
          data-testid="bulk-move"
          @click="moveDialog = { cameras: selectedCameras }"
        >
          <AppIcon
            name="move"
            small
          />{{ t('cameras.move') }}
        </button>
        <select
          v-model="groupTarget"
          class="input"
          style="width: auto; height: 28px"
          :aria-label="t('cameras.assignGroup')"
        >
          <option value="">
            {{ t('cameras.assignGroup') }}…
          </option>
          <option
            v-for="g in groups"
            :key="g.id"
            :value="g.id"
          >
            {{ g.name }}
          </option>
        </select>
        <button
          class="btn btn-sm"
          :disabled="!groupTarget"
          @click="assignGroup"
        >
          {{ t('common.apply') }}
        </button>
      </div>

      <div class="card">
        <div class="table-wrap">
          <table class="table">
            <thead>
              <tr>
                <th>
                  <input
                    type="checkbox"
                    :aria-label="t('common.selectAll')"
                    :checked="rows.length > 0 && checked.size === rows.length"
                    @change="toggleAll(($event.target as HTMLInputElement).checked)"
                  >
                </th>
                <th>{{ t('cameras.camera') }}</th>
                <th>{{ t('cameras.status') }}</th>
                <th>{{ t('cameras.location') }}</th>
                <th>{{ t('cameras.model') }}</th>
                <th class="hide-sm">
                  IP
                </th>
                <th />
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="c in liveRows"
                :key="c.id"
                :class="{ selected: checked.has(c.id) }"
                :draggable="canConfigure"
                data-testid="camera-row"
                @dragstart="onDragStart(c)"
              >
                <td>
                  <input
                    type="checkbox"
                    :checked="checked.has(c.id)"
                    :aria-label="c.name"
                    @change="toggle(c.id)"
                  >
                </td>
                <td class="wrap">
                  <RouterLink
                    :to="`/cameras/${c.id}`"
                    data-testid="camera-link"
                  >
                    <b>{{ c.name }}</b>
                  </RouterLink>
                </td>
                <td>
                  <span
                    :class="['badge', statusBadge(c.status)]"
                    data-testid="camera-status"
                  >{{ t(`cameras.status${c.status}`) }}</span>
                  <span
                    v-if="c.status === 'OFFLINE' && c.lastOfflineAt"
                    class="small muted"
                  > {{ t('cameras.since', { time: d(new Date(c.lastOfflineAt), 'time') }) }}</span>
                  <!-- US-25 AC2: camera lệch giờ quá 5 giây so với máy chủ -->
                  <span
                    v-if="c.clockDriftSeconds != null && Math.abs(c.clockDriftSeconds) > 5"
                    class="badge badge-warn no-dot"
                    data-testid="clock-skew"
                    :title="t('network.drift', { s: c.clockDriftSeconds.toFixed(1) })"
                  >{{ t('network.skewed') }}</span>
                </td>
                <td class="wrap">
                  {{ siteName[c.siteId] }} / <span class="muted">{{ zoneName[c.zoneId] }}</span>
                </td>
                <td>{{ [c.manufacturer, c.model].filter(Boolean).join(' ') || '—' }}</td>
                <td class="hide-sm mono">
                  {{ c.ipAddress ?? '—' }}
                </td>
                <td>
                  <template v-if="canConfigure">
                    <button
                      class="icon-btn tiny"
                      :title="t('common.rename')"
                      :aria-label="t('common.rename')"
                      @click="renameCamera(c)"
                    >
                      <AppIcon
                        name="edit"
                        small
                      />
                    </button>
                    <button
                      class="icon-btn tiny"
                      :title="t('common.delete')"
                      :aria-label="t('common.delete')"
                      @click="deleteCamera(c)"
                    >
                      <AppIcon
                        name="trash"
                        small
                      />
                    </button>
                  </template>
                </td>
              </tr>
              <tr v-if="!loading && rows.length === 0">
                <td
                  colspan="7"
                  class="muted"
                  style="text-align: center; padding: 24px"
                >
                  {{ t('cameras.empty') }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
      <p class="small muted">
        {{ t('cameras.count', { shown: rows.length, total }) }}
        <span v-if="canConfigure"> · {{ t('cameras.dragHint') }}</span>
      </p>
    </section>
  </div>

  <MoveCameraDialog
    v-if="moveDialog"
    :cameras="moveDialog.cameras"
    :sites="sites"
    :initial-zone-id="moveDialog.zoneId"
    @close="moveDialog = null"
    @done="moveDialog = null; toast.ok(t('cameras.moved')); reload()"
  />
  <AddCameraDialog
    v-if="addDialog === 'manual'"
    :sites="sites"
    :initial-zone-id="selection.kind === 'zone' ? selection.id : undefined"
    @close="addDialog = null"
    @saved="onAdded"
  />
  <ImportCamerasDialog
    v-if="addDialog === 'import'"
    @close="addDialog = null"
    @done="reload"
  />
  <AddRecorderDialog
    v-if="addDialog === 'recorder'"
    :sites="sites"
    :initial-zone-id="selection.kind === 'zone' ? selection.id : undefined"
    @close="addDialog = null"
    @saved="onAdded"
  />
  <ChangeCameraPasswordDialog
    v-if="passwordFor"
    :cameras="passwordFor"
    @close="passwordFor = null"
  />
  <DiscoveryWizard
    v-if="addDialog === 'discovery'"
    :sites="sites"
    @close="addDialog = null"
    @done="onAdded"
  />
  <PromptDialog
    v-if="prompt"
    :title="prompt.title"
    :label="prompt.label"
    :initial="prompt.initial"
    @close="prompt = null"
    @submit="runPrompt"
  />
</template>
