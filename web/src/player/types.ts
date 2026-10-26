export type PlayerMode = 'webrtc' | 'hls'

export interface PlayResult {
  mode: PlayerMode
  /** Thời gian từ lúc bắt đầu kết nối tới khi khung hình đầu tiên hiển thị (NFR-01: ≤ 3.000 ms) */
  firstFrameMs: number
}

export interface StreamPlayer {
  start(): Promise<PlayResult>
  stop(): Promise<void>
  /** Mẫu đo băng thông (US-11), gọi khoảng mỗi giây; null nếu trình phát không đo được */
  sample?(): Promise<import('./AdaptiveStream').BandwidthSample | null>
}

export class PlayerError extends Error {
  constructor(
    message: string,
    readonly mode: PlayerMode,
  ) {
    super(message)
    this.name = 'PlayerError'
  }
}

/** Chờ khung hình đầu tiên được vẽ; dùng requestVideoFrameCallback nếu trình duyệt hỗ trợ. */
export function waitFirstFrame(video: HTMLVideoElement, timeoutMs: number, mode: PlayerMode): Promise<number> {
  return new Promise((resolve, reject) => {
    let done = false
    const finish = () => {
      if (done) return
      done = true
      clearTimeout(timer)
      video.removeEventListener('loadeddata', finish)
      resolve(performance.now())
    }
    const timer = setTimeout(() => {
      if (done) return
      done = true
      video.removeEventListener('loadeddata', finish)
      reject(new PlayerError(`Không nhận được khung hình sau ${timeoutMs} ms`, mode))
    }, timeoutMs)

    const rvfc = (video as Partial<Pick<HTMLVideoElement, 'requestVideoFrameCallback'>>).requestVideoFrameCallback
    if (typeof rvfc === 'function') {
      rvfc.call(video, () => finish())
    } else {
      video.addEventListener('loadeddata', finish)
    }
  })
}
