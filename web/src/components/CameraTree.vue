<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import AppIcon from './AppIcon.vue'
import type { CameraGroup, SiteNode } from '@/services/api'

/** Cây Địa điểm → Khu vực và danh sách nhóm (US-05.3, design/cameras.html). */
export type TreeSelection = { kind: 'all' } | { kind: 'site' | 'zone' | 'group'; id: string }

const props = defineProps<{
  sites: SiteNode[]
  groups: CameraGroup[]
  selected: TreeSelection
  /** Số camera thấy được theo khu vực, theo địa điểm, theo nhóm */
  counts: { zone: Record<string, number>; site: Record<string, number>; group: Record<string, number>; total: number }
  canManageSites: boolean
  canConfigure: boolean
}>()
const emit = defineEmits<{
  select: [TreeSelection]
  addSite: []
  addZone: [siteId: string]
  renameZone: [zoneId: string, name: string]
  deleteZone: [zoneId: string]
  deleteSite: [siteId: string]
  addGroup: []
  dropCameras: [zoneId: string]
}>()
const { t } = useI18n()

function isSel(kind: string, id?: string) {
  const s = props.selected
  return s.kind === kind && (kind === 'all' || ('id' in s && s.id === id))
}
</script>

<template>
  <section class="card">
    <div class="card-head">
      <h3>{{ t('cameras.tree') }}</h3>
      <span class="spacer" />
      <button
        v-if="canManageSites"
        class="icon-btn"
        :title="t('cameras.addSite')"
        :aria-label="t('cameras.addSite')"
        data-testid="add-site"
        @click="emit('addSite')"
      >
        <AppIcon name="plus" />
      </button>
    </div>
    <div class="tree">
      <div
        :class="['node', { active: isSel('all') }]"
        role="button"
        tabindex="0"
        @click="emit('select', { kind: 'all' })"
        @keydown.enter="emit('select', { kind: 'all' })"
      >
        <AppIcon
          name="grid"
          small
        /> {{ t('cameras.allCameras') }} <span class="muted">({{ counts.total }})</span>
      </div>
      <details
        v-for="s in sites"
        :key="s.id"
        open
      >
        <summary
          :class="{ active: isSel('site', s.id) }"
          @click.prevent="emit('select', { kind: 'site', id: s.id })"
        >
          <AppIcon
            name="pin"
            small
          />
          <span class="grow">{{ s.name }}</span>
          <span class="muted">({{ counts.site[s.id] ?? 0 }})</span>
          <button
            v-if="s.manageable"
            class="icon-btn tiny"
            :title="t('cameras.addZone')"
            :aria-label="t('cameras.addZone')"
            @click.stop="emit('addZone', s.id)"
          >
            <AppIcon
              name="plus"
              small
            />
          </button>
          <button
            v-if="canManageSites && s.zones.length === 0"
            class="icon-btn tiny"
            :title="t('common.delete')"
            :aria-label="t('common.delete')"
            @click.stop="emit('deleteSite', s.id)"
          >
            <AppIcon
              name="trash"
              small
            />
          </button>
        </summary>
        <div class="children">
          <div
            v-for="z in s.zones"
            :key="z.id"
            :class="['node', { active: isSel('zone', z.id) }]"
            role="button"
            tabindex="0"
            :data-zone="z.id"
            @click="emit('select', { kind: 'zone', id: z.id })"
            @keydown.enter="emit('select', { kind: 'zone', id: z.id })"
            @dragover.prevent
            @drop.prevent="s.manageable && emit('dropCameras', z.id)"
          >
            <span class="grow">{{ z.name }}</span>
            <span class="muted">({{ counts.zone[z.id] ?? 0 }})</span>
            <template v-if="s.manageable">
              <button
                class="icon-btn tiny"
                :title="t('common.rename')"
                :aria-label="t('common.rename')"
                @click.stop="emit('renameZone', z.id, z.name)"
              >
                <AppIcon
                  name="edit"
                  small
                />
              </button>
              <button
                v-if="!counts.zone[z.id]"
                class="icon-btn tiny"
                :title="t('common.delete')"
                :aria-label="t('common.delete')"
                @click.stop="emit('deleteZone', z.id)"
              >
                <AppIcon
                  name="trash"
                  small
                />
              </button>
            </template>
          </div>
          <p
            v-if="s.zones.length === 0"
            class="small muted"
            style="padding: 4px 8px"
          >
            {{ t('cameras.noZones') }}
          </p>
        </div>
      </details>
    </div>
    <div
      class="card-body"
      style="border-top: 1px solid var(--border)"
    >
      <div class="row">
        <h3>{{ t('cameras.groups') }}</h3>
        <span class="spacer" />
        <button
          v-if="canConfigure"
          class="icon-btn tiny"
          :title="t('cameras.addGroup')"
          :aria-label="t('cameras.addGroup')"
          @click="emit('addGroup')"
        >
          <AppIcon
            name="plus"
            small
          />
        </button>
      </div>
      <div class="tree">
        <div
          v-for="g in groups"
          :key="g.id"
          :class="['node', { active: isSel('group', g.id) }]"
          role="button"
          tabindex="0"
          @click="emit('select', { kind: 'group', id: g.id })"
          @keydown.enter="emit('select', { kind: 'group', id: g.id })"
        >
          <AppIcon
            name="grid"
            small
          /> <span class="grow">{{ g.name }}</span> <span class="muted">({{ counts.group[g.id] ?? 0 }})</span>
        </div>
        <p
          v-if="groups.length === 0"
          class="small muted"
        >
          {{ t('cameras.noGroups') }}
        </p>
      </div>
    </div>
  </section>
</template>
