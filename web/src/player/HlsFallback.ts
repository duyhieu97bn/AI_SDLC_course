import Hls from 'hls.js'
import type { BandwidthSample } from './AdaptiveStream'
import { PlayerError, waitFirstFrame, type PlayResult, type StreamPlayer } from './types'

/**
 * Phát HLS (LL-HLS của MediaMTX) khi WebRTC không dùng được, ví dụ mạng chặn UDP (SADD 3.9.2).
 * Mọi yêu cầu playlist/segment đều gắn token xem, vì MediaMTX kiểm tra quyền trên từng yêu cầu.
 */
export class HlsFallback implements StreamPlayer {
  private hls: Hls | null = null

  constructor(
    private readonly video: HTMLVideoElement,
    private readonly url: string,
    private readonly token: string,
    private readonly timeoutMs = 8000,
  ) {}

  async start(): Promise<PlayResult> {
    const t0 = performance.now()
    this.video.muted = true
    this.video.playsInline = true
    this.video.autoplay = true

    const failed = new Promise<never>((_, reject) => {
      if (Hls.isSupported()) {
        const token = this.token
        const hls = new Hls({
          lowLatencyMode: true,
          xhrSetup: (xhr, url) => {
            xhr.open('GET', withToken(url, token), true)
          },
        })
        this.hls = hls
        hls.on(Hls.Events.ERROR, (_e, data) => {
          if (data.fatal) reject(new PlayerError(`HLS lỗi: ${data.details}`, 'hls'))
        })
        hls.loadSource(this.url)
        hls.attachMedia(this.video)
      } else if (this.video.canPlayType('application/vnd.apple.mpegurl')) {
        this.video.src = this.url // Safari phát HLS gốc
      } else {
        reject(new PlayerError('Trình duyệt không hỗ trợ HLS', 'hls'))
      }
    })

    const firstFrameAt = await Promise.race([waitFirstFrame(this.video, this.timeoutMs, 'hls'), failed])
    return { mode: 'hls', firstFrameMs: Math.round(firstFrameAt - t0) }
  }

  /** hls.js ước tính băng thông từ tốc độ tải đoạn (US-11). */
  async sample(): Promise<BandwidthSample | null> {
    if (!this.hls) return null
    return { estimateKbps: this.hls.bandwidthEstimate / 1000, receivedKbps: null }
  }

  async stop(): Promise<void> {
    this.hls?.destroy()
    this.hls = null
    this.video.removeAttribute('src')
    this.video.load()
  }
}

export function withToken(url: string, token: string): string {
  const u = new URL(url, window.location.href)
  u.searchParams.set('token', token)
  return u.href
}
