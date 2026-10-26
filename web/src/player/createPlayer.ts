import { WhepPlayer } from './WhepPlayer'
import type { PlayResult, StreamPlayer } from './types'

export type PlayMode = 'auto' | 'webrtc' | 'hls'

export interface StreamUrls {
  whepUrl: string
  hlsUrl: string
  token: string
}

/**
 * Phát một luồng theo chế độ chọn. Chế độ `auto`: thử WebRTC trước, lỗi thì chuyển HLS.
 * First frame ở chế độ auto tính cả thời gian thử WebRTC thất bại, vì đó là thời gian người dùng thực sự chờ.
 */
export async function play(
  video: HTMLVideoElement,
  urls: StreamUrls,
  mode: PlayMode,
): Promise<{ player: StreamPlayer; result: PlayResult }> {
  const t0 = performance.now()
  if (mode !== 'hls') {
    const whep = new WhepPlayer(video, urls.whepUrl, { timeoutMs: mode === 'auto' ? 4000 : 8000 })
    try {
      return { player: whep, result: await whep.start() }
    } catch (e) {
      await whep.stop()
      if (mode === 'webrtc') throw e
    }
  }
  // hls.js lớn (~500 kB), chỉ tải khi thật sự cần dự phòng
  const { HlsFallback } = await import('./HlsFallback')
  const hls = new HlsFallback(video, urls.hlsUrl, urls.token)
  const result = await hls.start()
  return { player: hls, result: { ...result, firstFrameMs: Math.round(performance.now() - t0) } }
}
