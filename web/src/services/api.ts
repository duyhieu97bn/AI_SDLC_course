import { http, qs } from './http'
import type { Permission } from '@/stores/auth'

// ---------------------------------------------------------------- Tổ chức (US-05)

export interface ZoneNode {
  id: string
  name: string
}
export interface SiteNode {
  id: string
  name: string
  address: string | null
  timezone: string
  zones: ZoneNode[]
  manageable: boolean
}
export interface CameraGroup {
  id: string
  name: string
  description: string | null
  cameraCount: number
}

export const orgApi = {
  sites: () => http.get<SiteNode[]>('/sites'),
  createSite: (name: string, address?: string) => http.post<{ id: string }>('/sites', { name, address }),
  updateSite: (id: string, body: Partial<Pick<SiteNode, 'name' | 'address' | 'timezone'>>) => http.patch(`/sites/${id}`, body),
  deleteSite: (id: string) => http.delete(`/sites/${id}`),
  createZone: (siteId: string, name: string) => http.post<{ id: string }>(`/sites/${siteId}/zones`, { name }),
  renameZone: (id: string, name: string) => http.patch(`/zones/${id}`, { name }),
  deleteZone: (id: string) => http.delete(`/zones/${id}`),
  groups: () => http.get<CameraGroup[]>('/camera-groups'),
  createGroup: (name: string, description?: string) => http.post<{ id: string }>('/camera-groups', { name, description }),
  setGroupMembers: (id: string, add: string[], remove: string[]) => http.put(`/camera-groups/${id}/cameras`, { add, remove }),
  moveCamera: (cameraId: string, zoneId: string) => http.post(`/cameras/${cameraId}/move`, { zoneId }),
}

// ---------------------------------------------------------------- Camera

export type CameraStatus = 'ONLINE' | 'OFFLINE' | 'UNKNOWN'
export interface Camera {
  id: string
  name: string
  siteId: string
  zoneId: string
  groupIds: string[]
  protocol: 'ONVIF' | 'RTSP'
  ipAddress: string | null
  port: number | null
  manufacturer: string | null
  model: string | null
  macAddress: string | null
  status: CameraStatus
  statusChangedAt: string | null
  lastOfflineAt: string | null
  verified: boolean
  firmware: string | null
  capabilities: Record<string, boolean>
  createdAt: string
  /** Đồng hồ camera trừ đồng hồ máy chủ (giây), đo mỗi giờ (US-25) */
  clockDriftSeconds?: number | null
  recorderId?: string | null
  connectorId?: string | null
}
export interface Page<T> {
  items: T[]
  total: number
  page: number
  size: number
}
export interface CameraQuery {
  siteId?: string | null
  zoneId?: string | null
  groupId?: string | null
  status?: string | null
  q?: string | null
  page?: number
  size?: number
}

export const cameraApi = {
  list: (q: CameraQuery = {}) => http.get<Page<Camera>>(`/cameras${qs({ ...q })}`),
  get: (id: string) => http.get<Camera>(`/cameras/${id}`),
}

// ---------------------------------------------------------------- Người dùng và phân quyền (US-32)

export type ScopeType = 'ORGANIZATION' | 'SITE' | 'GROUP' | 'CAMERA'
export type UserStatus = 'ACTIVE' | 'LOCKED' | 'DISABLED'
export interface User {
  id: string
  email: string
  displayName: string
  authSource: 'LOCAL' | 'DIRECTORY'
  status: UserStatus
  language: 'vi' | 'en'
  twoFactorEnabled: boolean
  lastLoginAt: string | null
  createdAt: string
}
export interface Role {
  id: string
  code: string | null
  name: string
  description: string | null
  system: boolean
  requireTwoFactor: boolean
  rank: number
  permissions: Permission[]
  assignedUsers: number
}
export interface Assignment {
  id?: string
  roleId: string
  roleName?: string
  scopeType: ScopeType
  scopeId: string | null
  source?: 'MANUAL' | 'DIRECTORY'
}

export const userApi = {
  list: (q?: string) => http.get<User[]>(`/users${qs({ q })}`),
  create: (body: { email: string; displayName: string; password: string; language: string; assignments: Assignment[] }) =>
    http.post<User>('/users', body),
  update: (id: string, body: Partial<Pick<User, 'displayName' | 'language' | 'status'>>) => http.patch<User>(`/users/${id}`, body),
  resetPassword: (id: string, password: string) => http.post(`/users/${id}/password`, { password }),
  roles: () => http.get<Role[]>('/roles'),
  assignments: (id: string) => http.get<Assignment[]>(`/users/${id}/assignments`),
  setAssignments: (id: string, items: Assignment[]) =>
    http.put<Assignment[]>(`/users/${id}/assignments`, items.map(({ roleId, scopeType, scopeId }) => ({ roleId, scopeType, scopeId }))),
}

// ---------------------------------------------------------------- PoC (S0)

export interface MediaToken {
  token: string
  whepUrl: string
  hlsUrl: string
  expiresAt: string
}

/** Chỉ có ở profile poc của Core (PocMediaTokenController). */
export function issuePocToken(path: string): Promise<MediaToken> {
  return http.post<MediaToken>('/poc/media-token', { path, action: 'READ' })
}

// ---------------------------------------------------------------- S2: thêm camera, dò tìm (US-01, US-02)

export interface StreamInfo {
  type: 'MAIN' | 'SUB'
  codec: string | null
  width: number | null
  height: number | null
  fps: number | null
  bitrateKbps: number | null
  bFrames: boolean
}
export interface ConnectionInput {
  protocol: 'ONVIF' | 'RTSP'
  host?: string
  port?: number
  username?: string
  password?: string
  streamUrl?: string
  /** Địa điểm có bộ kết nối thì kiểm tra qua đường hầm (ADR-10) */
  siteId?: string
}
export interface ConnectionResult {
  ok: boolean
  code: string | null
  message: string | null
  manufacturer: string | null
  model: string | null
  firmware: string | null
  macAddress: string | null
  streams: StreamInfo[]
  capabilities: Record<string, boolean>
  preview: { whepUrl: string; hlsUrl: string; token: string } | null
}
export interface Discovered {
  host: string
  port: number
  manufacturer: string | null
  model: string | null
  macAddress: string | null
  hardware: string | null
  alreadyAdded: boolean
}
export interface BulkResult {
  host: string
  ok: boolean
  cameraId: string | null
  code: string | null
  message: string | null
}

export const deviceApi = {
  testConnection: (c: ConnectionInput) => http.post<ConnectionResult>('/cameras/test-connection', c),
  create: (body: { zoneId: string; name?: string; connection: ConnectionInput; saveUnverified: boolean }) =>
    http.post<Camera>('/cameras', body),
  bulk: (body: { zoneId: string; username: string; password: string; devices: { host: string; port: number; name?: string }[] }) =>
    http.post<BulkResult[]>('/cameras/bulk', body),
  discover: (siteId: string, targets: string[]) => http.post<Discovered[]>(`/sites/${siteId}/discovery`, { targets }),
  rename: (id: string, name: string) => http.patch<Camera>(`/cameras/${id}`, { name }),
  remove: (id: string) => http.delete(`/cameras/${id}`),
  streams: (id: string) => http.get<StreamInfo[]>(`/cameras/${id}/streams`),
}

// ---------------------------------------------------------------- S2: nhật ký (US-33), 2FA (US-31)

export interface AuditEntry {
  id: string
  occurredAt: string
  actorId: string | null
  actorName: string | null
  action: string
  targetType: string | null
  targetId: string | null
  targetName: string | null
  ipAddress: string | null
  result: 'SUCCESS' | 'FAILURE'
  details: string
}
export interface AuditFilter {
  from?: string | null
  to?: string | null
  action?: string | null
  result?: string | null
  q?: string | null
  page?: number
  size?: number
}

export const auditApi = {
  search: (f: AuditFilter) => http.get<Page<AuditEntry>>(`/audit-logs${qs({ ...f })}`),
  exportUrl: (f: AuditFilter) => `/audit-logs/export${qs({ ...f, page: null, size: null })}`,
}

export const twoFactorApi = {
  status: () => http.get<{ enabled: boolean; required: boolean; remainingRecoveryCodes: number }>('/me/2fa'),
  setup: () => http.post<{ secret: string; otpauthUri: string }>('/me/2fa/setup', {}),
  enable: (code: string) => http.post<{ recoveryCodes: string[] }>('/me/2fa/enable', { code }),
  regenerate: (code: string) => http.post<{ recoveryCodes: string[] }>('/me/2fa/recovery-codes', { code }),
  disable: (code: string) => http.post('/me/2fa/disable', { code }),
  reset: (userId: string) => http.post(`/users/${userId}/2fa/reset`, {}),
  setRoleRequired: (roleId: string, required: boolean) => http.patch<Role>(`/roles/${roleId}/two-factor`, { required }),
}

// ---------------------------------------------------------------- S3: xem trực tiếp, PTZ, bố cục (US-08..13)

export type StreamKind = 'MAIN' | 'SUB'
export type StreamMode = 'AUTO' | 'HIGH' | 'LOW'
export interface LiveSession {
  cameraId: string
  cameraName: string
  status: CameraStatus
  ptz: boolean
  recording: boolean
  stream: StreamKind
  transcoded: boolean
  whepUrl: string
  hlsUrl: string
  token: string
  expiresAt: string
}
export interface PresetView {
  token: string
  name: string
  source: 'DEVICE' | 'CAMHUB'
}
export interface LayoutTile {
  position: number
  cameraId: string
  streamMode: StreamMode
}
export interface ViewLayout {
  id: string
  name: string
  gridSize: 1 | 4 | 9 | 16
  lastUsedAt: string | null
  tiles: LayoutTile[]
}

export const liveApi = {
  session: (cameraId: string, stream: StreamKind) => http.post<LiveSession>(`/cameras/${cameraId}/live-session`, { stream }),
  ptz: (cameraId: string, pan: number, tilt: number, zoom: number) => http.post(`/cameras/${cameraId}/ptz`, { pan, tilt, zoom }),
  ptzStop: (cameraId: string) => http.post(`/cameras/${cameraId}/ptz/stop`, {}),
  presets: (cameraId: string) => http.get<PresetView[]>(`/cameras/${cameraId}/presets`),
  savePreset: (cameraId: string, name: string) => http.post<PresetView>(`/cameras/${cameraId}/presets`, { name }),
  gotoPreset: (cameraId: string, token: string) => http.post(`/cameras/${cameraId}/presets/${encodeURIComponent(token)}/goto`, {}),
  removePreset: (cameraId: string, token: string) => http.delete(`/cameras/${cameraId}/presets/${encodeURIComponent(token)}`),
  layouts: () => http.get<ViewLayout[]>('/layouts'),
  createLayout: (l: Omit<ViewLayout, 'id' | 'lastUsedAt'>) => http.post<ViewLayout>('/layouts', l),
  updateLayout: (id: string, l: Omit<ViewLayout, 'id' | 'lastUsedAt'>) => http.put<ViewLayout>(`/layouts/${id}`, l),
  useLayout: (id: string) => http.post(`/layouts/${id}/use`, {}),
  deleteLayout: (id: string) => http.delete(`/layouts/${id}`),
}

// ---------------------------------------------------------------- S9: đàm thoại (US-15)

export interface TalkStart {
  sessionId: string
  /** Đường dẫn WebSocket (cùng nguồn) gửi PCM 16 bit mono ``sampleRate`` Hz */
  wsUrl: string
  codec: string
  sampleRate: number
  expiresInSeconds: number
}
export interface TalkEnd {
  sessionId: string
  durationSeconds: number
  audioSeconds: number
}

export const talkApi = {
  start: (cameraId: string) => http.post<TalkStart>(`/cameras/${cameraId}/talk`, {}),
  end: (cameraId: string, sessionId: string) =>
    http.post<TalkEnd>(`/cameras/${cameraId}/talk/${encodeURIComponent(sessionId)}/end`, {}),
}

// ---------------------------------------------------------------- S4: ghi hình, xem lại, lưu trữ, cấu hình luồng

export type RecordingMode = 'CONTINUOUS' | 'SCHEDULED' | 'EVENT' | 'OFF'
export interface ScheduleSlot {
  day: number
  from: string
  to: string
}
export interface RecordingPolicy {
  cameraId: string
  mode: RecordingMode
  schedule: ScheduleSlot[]
  preEventSeconds: number
  postEventSeconds: number
}
export interface Timeline {
  recordings: { start: string; end: string }[]
  events: string[]
  holds: { id: string; start: string; end: string; reason: string }[]
}
export interface PlaybackSession {
  cameraId: string
  requestedAt: string
  start: string
  end: string
  jumped: boolean
  url: string
  token: string
  expiresAt: string
}
export interface EvidenceHold {
  id: string
  cameraId: string
  startAt: string
  endAt: string
  reason: string
  createdAt: string
  releasedAt: string | null
}
export interface EncoderConfig {
  token: string
  name: string
  codec: string
  width: number
  height: number
  fps: number
  bitrateKbps: number
  govLength: number | null
  bFrames: boolean
  options: {
    codecs: string[]
    resolutions: { width: number; height: number }[]
    fpsRange: { min: number; max: number } | null
    bitrateRange: { min: number; max: number } | null
    govLengthRange: { min: number; max: number } | null
  } | null
}
export interface EncoderChange {
  configurationToken: string
  codec?: string
  width?: number
  height?: number
  fps?: number
  bitrateKbps?: number
  govLength?: number
}
export interface ApplyResult {
  result: 'SUCCESS' | 'PARTIAL' | 'FAILED'
  parameters: { configurationToken: string; parameter: string; ok: boolean; message: string | null }[]
}

export const recordingApi = {
  policy: (cameraId: string) => http.get<RecordingPolicy>(`/cameras/${cameraId}/recording-policy`),
  setPolicy: (cameraId: string, p: Omit<RecordingPolicy, 'cameraId'>) => http.put<RecordingPolicy>(`/cameras/${cameraId}/recording-policy`, p),
  timeline: (cameraId: string, from: string, to: string) => http.get<Timeline>(`/cameras/${cameraId}/timeline${qs({ from, to })}`),
  days: (cameraId: string, month: string) => http.get<string[]>(`/cameras/${cameraId}/recording-days${qs({ month })}`),
  playback: (cameraId: string, at: string) => http.post<PlaybackSession>(`/cameras/${cameraId}/playback-session`, { at }),
  retention: () => http.get<{ defaultRetentionDays: number }>('/retention-policy'),
  setRetention: (days: number) => http.put('/retention-policy', { defaultRetentionDays: days }),
  cameraRetention: (cameraId: string) => http.get<{ override: number | ''; defaultRetentionDays: number }>(`/cameras/${cameraId}/retention`),
  setCameraRetention: (cameraId: string, days: number | null) => http.put(`/cameras/${cameraId}/retention`, { retentionDays: days }),
  holds: (cameraId: string) => http.get<EvidenceHold[]>(`/cameras/${cameraId}/evidence-holds`),
  hold: (cameraId: string, startAt: string, endAt: string, reason: string) =>
    http.post<{ id: string }>('/evidence-holds', { cameraId, startAt, endAt, reason }),
  release: (id: string) => http.post(`/evidence-holds/${id}/release`, {}),
  streamConfig: (cameraId: string) => http.get<EncoderConfig[]>(`/cameras/${cameraId}/config/stream`),
  applyStream: (cameraId: string, changes: EncoderChange[]) => http.put<ApplyResult>(`/cameras/${cameraId}/config/stream`, { changes }),
}

// ---------------------------------------------------------------- Xuất clip (US-20)

export type ExportStatus = 'QUEUED' | 'PROCESSING' | 'READY' | 'FAILED' | 'EXPIRED'
export interface ExportJob {
  id: string
  cameraId: string
  cameraName: string
  startAt: string
  endAt: string
  status: ExportStatus
  requestedAt: string
  completedAt: string | null
  expiresAt: string | null
  sizeBytes: number | null
  errorCode: string | null
  errorMessage: string | null
}

export const exportApi = {
  create: (cameraId: string, startAt: string, endAt: string) => http.post<ExportJob>('/exports', { cameraId, startAt, endAt }),
  mine: () => http.get<ExportJob[]>('/exports'),
  link: (id: string) => http.post<{ url: string; expiresAt: string }>(`/exports/${id}/download-link`, {}),
}

// ---------------------------------------------------------------- Cảnh báo, thông báo (US-34, US-35)

export type AlertType = 'CAMERA_OFFLINE' | 'SITE_OFFLINE' | 'STORAGE_THRESHOLD' | 'MOTION'
export type AlertStatus = 'NEW' | 'IN_PROGRESS' | 'RECOVERED' | 'CLOSED'
export interface Alert {
  id: string
  type: AlertType
  severity: 'INFO' | 'WARNING' | 'CRITICAL'
  status: AlertStatus
  sourceKey: string
  cameraId: string | null
  siteId: string | null
  title: string
  details: Record<string, unknown>
  occurredAt: string
  recoveredAt: string | null
  assigneeId?: string | null
  closedAt?: string | null
  outcome?: string | null
  outcomeNote?: string | null
  snapshotStatus?: 'PENDING' | 'READY' | 'FAILED' | null
}
export interface AlertRule {
  type: AlertType
  enabled: boolean
  params: Record<string, number>
  channels: string[]
}
export interface AppNotification {
  id: string
  title: string
  body: string | null
  link: string | null
  alertId: string | null
  createdAt?: string
  readAt?: string | null
}

export const alertApi = {
  list: (status: 'OPEN' | 'ALL', type?: AlertType) => http.get<Alert[]>(`/alerts${qs({ status, type })}`),
  rules: () => http.get<AlertRule[]>('/alert-rules'),
  setRule: (r: AlertRule) => http.put<AlertRule>(`/alert-rules/${r.type}`, { enabled: r.enabled, params: r.params, channels: r.channels }),
}

export const notificationApi = {
  list: () => http.get<AppNotification[]>('/me/notifications'),
  unread: () => http.get<{ count: number }>('/me/notifications/unread-count'),
  read: (id: string) => http.post(`/me/notifications/${id}/read`, {}),
  readAll: () => http.post('/me/notifications/read-all', {}),
}

// ---------------------------------------------------------------- Hệ thống (US-39, US-40)

export interface StorageVolume {
  name: string
  totalBytes: number
  usedBytes: number
  usedPercent: number
  daysLeft: number | null
  measuredAt: string
}
export interface BackupEntry {
  label: string
  type: string
  startedAt: string
  finishedAt: string
  sizeBytes: number
}
export interface BackupStatus {
  available: boolean
  generatedAt?: string
  error?: string
  postgres?: {
    status: 'OK' | 'ERROR'
    message: string | null
    lastBackupAt: string | null
    lastBackupType: string | null
    backupCount: number
    fullCount: number
    lastWalArchivedAt: string | null
    repoSizeBytes: number | null
    backups: BackupEntry[]
  }
  minio?: { status: 'OK' | 'ERROR' | 'UNKNOWN'; message: string | null; lastMirrorAt: string | null; objects: number | null }
}
export interface SystemOverview {
  camerasOnline: number
  camerasTotal: number
  offlineCameras: { id: string; name: string; siteName: string; since: string | null }[]
  openAlerts: number
  storage: StorageVolume[]
  objectStorageUp: boolean
  connectors: unknown[]
  backup: BackupStatus
}

export const systemApi = {
  overview: () => http.get<SystemOverview>('/system/overview'),
  backups: () => http.get<BackupStatus>('/system/backups'),
}

// ---------------------------------------------------------------- Vùng chuyển động, vùng che (US-26)

export interface Point {
  x: number
  y: number
}
export interface ZonesConfig {
  motion: {
    supported: boolean
    mode: 'CELL' | 'POLYGON' | null
    grid: { columns: number; rows: number } | null
    cells: boolean[][] | null
    polygons: Point[][]
    sensitivity: number | null
    maxPolygons: number | null
  }
  masks: {
    supported: boolean
    maxMasks: number | null
    maxPoints: number | null
    items: { token: string | null; points: Point[]; enabled: boolean }[]
  }
}

export const zoneApi = {
  get: (cameraId: string) => http.get<ZonesConfig>(`/cameras/${cameraId}/config/zones`),
  setMotion: (cameraId: string, body: { mode: 'CELL' | 'POLYGON'; cells?: boolean[][]; polygons?: Point[][]; sensitivity?: number | null }) =>
    http.put<ZonesConfig>(`/cameras/${cameraId}/config/zones/motion`, body),
  setMasks: (cameraId: string, items: { points: Point[]; enabled: boolean }[]) =>
    http.put<ZonesConfig>(`/cameras/${cameraId}/config/zones/masks`, { items }),
}

// ---------------------------------------------------------------- S6: bộ kết nối địa điểm, đầu ghi, bảo trì

export type ConnectorStatus = 'PENDING_ACTIVATION' | 'ONLINE' | 'OFFLINE' | 'REVOKED'
export interface SiteConnector {
  id: string
  siteId: string
  siteName: string
  name: string
  status: ConnectorStatus
  lastSeenAt: string | null
  tunnelUp: boolean
  version: string | null
  hostname: string | null
  rxKbps: number | null
  txKbps: number | null
  codeExpiresAt: string | null
  createdAt: string
}
export interface ConnectorCreated {
  connector: SiteConnector
  activationCode: string
  expiresAt: string
  installCommand: string
}

export const connectorApi = {
  list: () => http.get<SiteConnector[]>('/connectors'),
  create: (siteId: string, name?: string) => http.post<ConnectorCreated>('/connectors', { siteId, name }),
  newCode: (id: string) => http.post<ConnectorCreated>(`/connectors/${id}/activation-code`, {}),
  revoke: (id: string) => http.delete(`/connectors/${id}`),
}

export interface RecorderChannel {
  channel: number
  name: string
  hasSignal: boolean
  resolution: { width: number; height: number } | null
  rtspUrl: string | null
}
export interface RecorderInfo {
  manufacturer: string | null
  model: string | null
  channelCount: number
  channels: RecorderChannel[]
}
export interface RecorderConnection {
  host: string
  port: number
  username: string
  password: string
}

export const recorderApi = {
  channels: (siteId: string, connection: RecorderConnection) => http.post<RecorderInfo>('/recorders/channels', { siteId, connection }),
  create: (zoneId: string, name: string, connection: RecorderConnection, channels: { channel: number; name: string }[]) =>
    http.post<{ recorderId: string; cameraIds: string[] }>('/recorders', { zoneId, name, connection, channels }),
}

export interface PasswordResult {
  cameraId: string
  name: string | null
  ok: boolean
  code: string | null
  message: string | null
}
export interface RebootStatus {
  cameraId: string
  name: string
  state: 'REBOOTING' | 'ONLINE' | 'OFFLINE' | 'UNKNOWN' | 'TIMEOUT'
  requestedAt: string | null
  at: string | null
}

export const maintenanceApi = {
  changePasswords: (cameraIds: string[], newPassword: string) => http.post<PasswordResult[]>('/cameras/password', { cameraIds, newPassword }),
  reboot: (cameraId: string) => http.post<RebootStatus>(`/cameras/${cameraId}/reboot`, {}),
  rebootStatus: (cameraId: string) => http.get<RebootStatus>(`/cameras/${cameraId}/reboot`),
}

// ---------------------------------------------------------------- S7: xử lý cảnh báo, quy tắc chuyển động, kênh nhận

export type AlertOutcome = 'RESOLVED' | 'FALSE_ALARM' | 'ESCALATED' | 'NO_ACTION_NEEDED'
export interface Person {
  id: string
  name: string
}
export interface AlertDetail {
  alert: Alert
  history: { from: AlertStatus | null; to: AlertStatus; actorId: string | null; note: string | null; at: string }[]
  handovers: { from: Person; to: Person; reason: string; at: string }[]
  assignee: Person | null
  canAct: boolean
}
export interface MotionSlot {
  days: number[]
  from: string
  to: string
}
export interface MotionRule {
  id?: string
  name: string
  enabled: boolean
  scopeType: 'ORGANIZATION' | 'SITE' | 'ZONE' | 'CAMERA'
  scopeId: string | null
  schedule: MotionSlot[]
  cooldownSeconds: number
  channels: string[]
  includeSnapshot: boolean
}
export interface ChannelBinding {
  id: string
  channel: 'TELEGRAM' | 'ZALO'
  address: string
  enabled: boolean
}
export interface ChannelConfig {
  channel: 'EMAIL' | 'TELEGRAM' | 'ZALO'
  enabled: boolean
  hasSecret: boolean
}

export const alertWorkflowApi = {
  detail: (id: string) => http.get<AlertDetail>(`/alerts/${id}`),
  acknowledge: (id: string) => http.post<AlertDetail>(`/alerts/${id}/acknowledge`, {}),
  reassign: (id: string, userId: string, reason: string) => http.post<AlertDetail>(`/alerts/${id}/reassign`, { userId, reason }),
  close: (id: string, outcome: AlertOutcome | '', note: string) => http.post<AlertDetail>(`/alerts/${id}/close`, { outcome: outcome || null, note }),
  assignees: (id: string) => http.get<Person[]>(`/alerts/${id}/assignees`),
  snapshotUrl: (id: string) => `/api/v1/alerts/${id}/snapshot`,
  motionRules: () => http.get<MotionRule[]>('/motion-rules'),
  saveRule: (r: MotionRule) => (r.id ? http.put<MotionRule>(`/motion-rules/${r.id}`, r) : http.post<MotionRule>('/motion-rules', r)),
  deleteRule: (id: string) => http.delete(`/motion-rules/${id}`),
}

export const channelApi = {
  mine: () => http.get<ChannelBinding[]>('/me/channels'),
  bind: (channel: string, address: string, enabled = true) => http.put(`/me/channels/${channel}`, { address, enabled }),
  unbind: (channel: string) => http.delete(`/me/channels/${channel}`),
  configs: () => http.get<ChannelConfig[]>('/notification-channels'),
  configure: (channel: string, enabled: boolean, secret?: string) => http.put(`/notification-channels/${channel}`, { enabled, secret }),
}

export interface ImagingSettings {
  videoSourceToken: string
  brightness: number | null
  contrast: number | null
  irMode: 'AUTO' | 'DAY' | 'NIGHT' | null
  wdr: boolean | null
  options: { brightness: { min: number; max: number } | null; contrast: { min: number; max: number } | null; irModes: string[]; wdr: boolean } | null
}
export type ImagingChange = Partial<Pick<ImagingSettings, 'brightness' | 'contrast' | 'irMode' | 'wdr'>>

export const imageApi = {
  get: (cameraId: string) => http.get<ImagingSettings>(`/cameras/${cameraId}/config/image`),
  set: (cameraId: string, change: ImagingChange) => http.put<ImagingSettings>(`/cameras/${cameraId}/config/image`, change),
}

export interface EdgeTimeline {
  supported: boolean
  reason: string | null
  ranges: { start: string; end: string; recordingToken: string }[]
}
export interface EdgeSession {
  cameraId: string
  start: string
  recordingToken: string
  whepUrl: string
  hlsUrl: string
  token: string
}

export const edgeApi = {
  timeline: (cameraId: string, from: string, to: string) => http.get<EdgeTimeline>(`/cameras/${cameraId}/edge-timeline${qs({ from, to })}`),
  open: (cameraId: string, at: string) => http.post<EdgeSession>(`/cameras/${cameraId}/edge-playback-session`, { at }),
}

// ---------------------------------------------------------------- S8: vai trò tùy chỉnh, LDAP/AD, cấu hình mẫu

export const roleApi = {
  create: (name: string, description: string, permissions: Permission[], copyFrom?: string) =>
    http.post<Role>('/roles', { name, description, permissions, copyFrom }),
  update: (id: string, name: string, description: string, permissions: Permission[]) =>
    http.put<Role>(`/roles/${id}`, { name, description, permissions }),
  remove: (id: string) => http.delete(`/roles/${id}`),
}

export interface DirectoryConnection {
  enabled: boolean
  url: string
  bindDn: string
  hasBindPassword: boolean
  userBase: string
  userFilter: string
  groupBase: string
  memberAttribute: string
  displayNameAttribute: string
  mailAttribute: string
  disabledGroupDn: string | null
  lastSyncAt: string | null
  lastSyncStatus: string | null
  lastSyncMessage: string | null
}
export interface DirectoryMapping {
  id: string | null
  groupDn: string
  roleId: string
  scopeType: 'ORGANIZATION' | 'SITE' | 'GROUP' | 'CAMERA'
  scopeId: string | null
}

export const directoryApi = {
  get: () => http.get<DirectoryConnection | null>('/directory'),
  save: (c: DirectoryConnection & { bindPassword?: string }) => http.put<DirectoryConnection>('/directory', c),
  test: () => http.post<{ ok: boolean; users?: number; message?: string }>('/directory/test', {}),
  mappings: () => http.get<DirectoryMapping[]>('/directory/mappings'),
  saveMappings: (m: DirectoryMapping[]) => http.put<DirectoryMapping[]>('/directory/mappings', m),
  sync: () => http.post<{ checked: number; updated: number; disabled: number; message: string }>('/directory/sync', {}),
}

export interface TemplateDetail {
  section: string
  parameter: string | null
  status: 'OK' | 'SKIPPED' | 'ERROR'
  message: string | null
}
export interface ConfigTemplate {
  id?: string
  name: string
  description: string | null
  recording: { mode: RecordingMode; schedule: ScheduleSlot[]; preEventSeconds: number; postEventSeconds: number } | null
  stream: { codec: string | null; width: number | null; height: number | null; fps: number | null; bitrateKbps: number | null; govLength: number | null } | null
  image: { brightness: number | null; contrast: number | null; irMode: string | null; wdr: boolean | null } | null
}
export interface TemplateApplication {
  id: string
  templateId: string
  templateName: string
  startedAt: string
  finishedAt: string | null
  results: { cameraId: string; cameraName: string; result: 'SUCCESS' | 'PARTIAL' | 'FAILED'; details: TemplateDetail[] }[]
}

export const templateApi = {
  list: () => http.get<ConfigTemplate[]>('/config-templates'),
  save: (t: ConfigTemplate) => (t.id ? http.put<ConfigTemplate>(`/config-templates/${t.id}`, t) : http.post<ConfigTemplate>('/config-templates', t)),
  remove: (id: string) => http.delete(`/config-templates/${id}`),
  apply: (id: string, cameraIds: string[]) => http.post<TemplateApplication>(`/config-templates/${id}/apply`, { cameraIds }),
  reapply: (applicationId: string) => http.post<TemplateApplication>(`/template-applications/${applicationId}/reapply-failed`, {}),
}

// ---------------------------------------------------------------- US-25: mạng và thời gian

export interface NetworkInfo {
  ipAddress: string | null
  prefixLength: number | null
  gateway: string | null
  dns: string[]
  macAddress: string | null
  hostname: string | null
  ntpServers: string[]
  ntpFromDhcp: boolean
  fromDhcp: boolean
  timeMode: 'NTP' | 'MANUAL' | null
  timezone: string | null
  deviceTime: string
  clockDriftSeconds: number
}

export const networkApi = {
  get: (cameraId: string) => http.get<NetworkInfo>(`/cameras/${cameraId}/config/network`),
  set: (cameraId: string, body: { dhcp: boolean; address?: string; prefixLength?: number; gateway?: string | null; dns?: string[] | null }) =>
    http.put<{ rebootNeeded: boolean; newHost: string | null }>(`/cameras/${cameraId}/config/network`, body),
  setTime: (cameraId: string, body: { mode: 'NTP' | 'MANUAL'; ntpServers?: string[]; utc?: string; timezone?: string }) =>
    http.put<NetworkInfo>(`/cameras/${cameraId}/config/time`, body),
}

// ---------------------------------------------------------------- US-07: nhập camera từ tệp

export interface ImportRow {
  line: number
  name: string | null
  site: string | null
  zone: string | null
  protocol: string
  host: string | null
  port: number | null
  rtspUrl: string | null
  errors: string[]
}
export interface ImportResult {
  line: number
  name: string | null
  ok: boolean
  cameraId: string | null
  code: string | null
  message: string | null
}

function fileForm(file: File): FormData {
  const f = new FormData()
  f.append('file', file)
  return f
}

export const importApi = {
  preview: (file: File) => http.post<{ total: number; valid: number; rows: ImportRow[] }>('/cameras/import/preview', fileForm(file)),
  run: (file: File, saveUnverified: boolean) => http.post<ImportResult[]>(`/cameras/import?saveUnverified=${saveUnverified}`, fileForm(file)),
}
