import { defineStore } from 'pinia'
import { ref } from 'vue'
import { Client, type StompSubscription } from '@stomp/stompjs'
import { currentToken } from '@/services/http'
import type { AppNotification, CameraStatus, ExportJob, RebootStatus, SiteConnector } from '@/services/api'

export interface CameraStatusEvent {
  cameraId: string
  status: CameraStatus
  since: string
  reason: string | null
}

/**
 * Kênh thời gian thực WebSocket STOMP tại /ws (SADD 3.7.2). Tự kết nối lại; access token gửi trong frame CONNECT
 * và lấy mới mỗi lần kết nối (token chỉ sống 15 phút).
 */
export const useRealtimeStore = defineStore('realtime', () => {
  const connected = ref(false)
  /** Trạng thái mới nhất nhận qua WebSocket, theo cameraId (US-04) */
  const cameraStatus = ref<Record<string, CameraStatusEvent>>({})
  /** Thông báo bị người khác giành quyền điều khiển PTZ (US-12) */
  const ptzTaken = ref<{ cameraId: string; takenBy: string; at: number } | null>(null)
  /** Thông báo mới nhất (chuông, toast – US-42) và cập nhật trạng thái clip xuất (US-20) */
  const lastNotification = ref<(AppNotification & { at: number }) | null>(null)
  const exportUpdate = ref<ExportJob | null>(null)
  /** Trạng thái bộ kết nối địa điểm (US-06) và kết quả khởi động lại camera (US-28) */
  const connectorUpdate = ref<SiteConnector | null>(null)
  const rebootUpdate = ref<RebootStatus | null>(null)
  let client: Client | null = null
  let orgId: string | null = null
  const subs: StompSubscription[] = []

  function start(organizationId: string) {
    if (client && orgId === organizationId) return
    stop()
    orgId = organizationId
    const url = `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws`
    client = new Client({
      brokerURL: url,
      reconnectDelay: 3000,
      heartbeatIncoming: 20000,
      heartbeatOutgoing: 20000,
      beforeConnect: (c) => {
        c.connectHeaders = { Authorization: `Bearer ${currentToken() ?? ''}` }
      },
      onConnect: () => {
        connected.value = true
        subs.push(
          client!.subscribe(`/topic/org/${organizationId}/camera-status`, (m) => {
            const e = JSON.parse(m.body) as CameraStatusEvent
            cameraStatus.value = { ...cameraStatus.value, [e.cameraId]: e }
          }),
          client!.subscribe('/user/queue/ptz', (m) => {
            const e = JSON.parse(m.body) as { cameraId: string; takenBy: string }
            ptzTaken.value = { ...e, at: Date.now() }
          }),
          client!.subscribe('/user/queue/notifications', (m) => {
            lastNotification.value = { ...(JSON.parse(m.body) as AppNotification), at: Date.now() }
          }),
          client!.subscribe('/user/queue/exports', (m) => {
            exportUpdate.value = JSON.parse(m.body) as ExportJob
          }),
          client!.subscribe(`/topic/org/${organizationId}/connector-status`, (m) => {
            connectorUpdate.value = JSON.parse(m.body) as SiteConnector
          }),
          client!.subscribe('/user/queue/reboot', (m) => {
            rebootUpdate.value = JSON.parse(m.body) as RebootStatus
          }),
        )
      },
      onWebSocketClose: () => {
        connected.value = false
        subs.length = 0
      },
    })
    client.activate()
  }

  function stop() {
    subs.forEach((s) => s.unsubscribe())
    subs.length = 0
    void client?.deactivate()
    client = null
    connected.value = false
  }

  return { connected, cameraStatus, ptzTaken, lastNotification, exportUpdate, connectorUpdate, rebootUpdate, start, stop }
})

/** Tổ chức trong access token (claim "org"), để đăng ký đúng kênh của Tổ chức. */
export function organizationOfToken(token: string | null): string | null {
  if (!token) return null
  try {
    const payload = JSON.parse(atob(token.split('.')[1]!.replace(/-/g, '+').replace(/_/g, '/'))) as { org?: string }
    return payload.org ?? null
  } catch {
    return null
  }
}
