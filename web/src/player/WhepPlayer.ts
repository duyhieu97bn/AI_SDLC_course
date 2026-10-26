import { estimateFromWebRtc, type BandwidthSample, type WebRtcCounters } from './AdaptiveStream'
import { PlayerError, waitFirstFrame, type PlayResult, type StreamPlayer } from './types'

export interface WhepOptions {
  /** Thời gian tối đa chờ khung hình đầu tiên trước khi coi là lỗi (để chuyển sang HLS) */
  timeoutMs?: number
  /** Thời gian tối đa chờ thu thập ICE candidate trước khi gửi offer */
  iceGatheringMs?: number
  fetchFn?: typeof fetch
  createPeerConnection?: (config: RTCConfiguration) => RTCPeerConnection
}

/**
 * Phát luồng WebRTC qua WHEP (RFC 9725) từ MediaMTX.
 * Gửi offer một lần sau khi thu thập ICE (không trickle) cho đơn giản; MediaMTX chạy cùng mạng LAN với người xem
 * hoặc sau Nginx nên đủ nhanh. Token xem nằm sẵn trong query của URL do Core cấp.
 */
export class WhepPlayer implements StreamPlayer {
  private pc: RTCPeerConnection | null = null
  private sessionUrl: string | null = null
  private readonly fetchFn: typeof fetch
  private readonly createPc: (config: RTCConfiguration) => RTCPeerConnection

  constructor(
    private readonly video: HTMLVideoElement,
    private readonly url: string,
    private readonly options: WhepOptions = {},
  ) {
    this.fetchFn = options.fetchFn ?? ((...args) => fetch(...args))
    this.createPc = options.createPeerConnection ?? ((c) => new RTCPeerConnection(c))
  }

  async start(): Promise<PlayResult> {
    const t0 = performance.now()
    const pc = this.createPc({ bundlePolicy: 'max-bundle' })
    this.pc = pc
    pc.addTransceiver('video', { direction: 'recvonly' })
    pc.addTransceiver('audio', { direction: 'recvonly' })

    const stream = new MediaStream()
    pc.ontrack = (e) => {
      stream.addTrack(e.track)
      this.video.srcObject = stream
    }

    this.video.muted = true
    this.video.playsInline = true
    this.video.autoplay = true

    await pc.setLocalDescription(await pc.createOffer())
    await waitIceGathering(pc, this.options.iceGatheringMs ?? 1500)

    const res = await this.fetchFn(this.url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/sdp' },
      body: pc.localDescription?.sdp ?? '',
    })
    if (res.status !== 201) {
      throw new PlayerError(`WHEP trả về ${res.status}`, 'webrtc')
    }
    const location = res.headers.get('Location')
    if (location) {
      this.sessionUrl = new URL(location, new URL(this.url, window.location.href)).href
    }
    await pc.setRemoteDescription({ type: 'answer', sdp: await res.text() })

    const firstFrameAt = await waitFirstFrame(this.video, this.options.timeoutMs ?? 5000, 'webrtc')
    return { mode: 'webrtc', firstFrameMs: Math.round(firstFrameAt - t0) }
  }

  private last: WebRtcCounters | null = null

  /** Đếm byte/gói nhận và băng thông khả dụng từ getStats() để quyết định chuyển luồng (US-11). */
  async sample(): Promise<BandwidthSample | null> {
    if (!this.pc) return null
    const report = await this.pc.getStats()
    const cur: WebRtcCounters = { at: performance.now(), bytesReceived: 0, packetsReceived: 0, packetsLost: 0, availableIncomingKbps: null }
    report.forEach((s: Record<string, unknown>) => {
      if (s.type === 'inbound-rtp' && s.kind === 'video') {
        cur.bytesReceived += Number(s.bytesReceived ?? 0)
        cur.packetsReceived += Number(s.packetsReceived ?? 0)
        cur.packetsLost += Number(s.packetsLost ?? 0)
      } else if (s.type === 'candidate-pair' && s.nominated && typeof s.availableIncomingBitrate === 'number') {
        cur.availableIncomingKbps = s.availableIncomingBitrate / 1000
      }
    })
    const prev = this.last
    this.last = cur
    return prev ? estimateFromWebRtc(prev, cur) : null
  }

  async stop(): Promise<void> {
    this.last = null
    this.pc?.close()
    this.pc = null
    this.video.srcObject = null
    if (this.sessionUrl) {
      const url = this.sessionUrl
      this.sessionUrl = null
      // Báo MediaMTX đóng phiên ngay; nếu lỗi thì MediaMTX tự đóng khi phát hiện peer ngắt
      await this.fetchFn(url, { method: 'DELETE' }).catch(() => undefined)
    }
  }
}

function waitIceGathering(pc: RTCPeerConnection, maxMs: number): Promise<void> {
  if (pc.iceGatheringState === 'complete') return Promise.resolve()
  return new Promise((resolve) => {
    const timer = setTimeout(done, maxMs)
    function done() {
      clearTimeout(timer)
      pc.removeEventListener('icegatheringstatechange', onChange)
      resolve()
    }
    function onChange() {
      if (pc.iceGatheringState === 'complete') done()
    }
    pc.addEventListener('icegatheringstatechange', onChange)
  })
}
