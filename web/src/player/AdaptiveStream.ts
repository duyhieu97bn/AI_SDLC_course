import type { StreamKind } from '@/services/api'

/**
 * Tự chuyển luồng theo băng thông (US-11): ở chế độ "Tự động", đang phát luồng chính mà băng thông ước tính thấp hơn
 * bitrate luồng chính liên tục 5 giây thì chuyển sang luồng phụ; quay lại luồng chính khi băng thông ≥ 1,5 lần bitrate
 * luồng chính liên tục 30 giây. Chế độ "Cao"/"Thấp" do người dùng chọn thì không tự chuyển (AC2) – VideoTile không gọi.
 */
export interface AdaptiveOptions {
  downAfterMs: number
  upAfterMs: number
  upFactor: number
}

export const DEFAULT_ADAPTIVE: AdaptiveOptions = { downAfterMs: 5_000, upAfterMs: 30_000, upFactor: 1.5 }

/** Một mẫu đo mỗi giây. */
export interface BandwidthSample {
  /** Băng thông khả dụng ước tính (kbps); null nếu chưa đo được */
  estimateKbps: number | null
  /** Bitrate đang nhận (kbps) */
  receivedKbps: number | null
}

export class AdaptiveController {
  private current: StreamKind
  private mainKbps: number
  private since: number | null = null

  constructor(
    initial: StreamKind,
    mainKbps: number,
    private readonly opts: AdaptiveOptions = DEFAULT_ADAPTIVE,
  ) {
    this.current = initial
    this.mainKbps = mainKbps
  }

  get stream(): StreamKind {
    return this.current
  }

  get mainBitrateKbps(): number {
    return this.mainKbps
  }

  /** Nhận một mẫu; trả về luồng cần chuyển sang, hoặc null nếu giữ nguyên. */
  sample(s: BandwidthSample, now: number): StreamKind | null {
    // Đang phát luồng chính: học bitrate thực tế của luồng chính (đỉnh gần đây)
    if (this.current === 'MAIN' && s.receivedKbps != null && s.receivedKbps > this.mainKbps) {
      this.mainKbps = s.receivedKbps
    }
    if (s.estimateKbps == null) {
      this.since = null
      return null
    }
    const bad = this.current === 'MAIN' && s.estimateKbps < this.mainKbps
    const good = this.current === 'SUB' && s.estimateKbps >= this.opts.upFactor * this.mainKbps
    if (!bad && !good) {
      this.since = null
      return null
    }
    this.since ??= now
    const wait = this.current === 'MAIN' ? this.opts.downAfterMs : this.opts.upAfterMs
    if (now - this.since < wait) return null
    this.since = null
    this.current = this.current === 'MAIN' ? 'SUB' : 'MAIN'
    return this.current
  }
}

/** Số liệu thô lấy từ RTCPeerConnection.getStats() giữa hai lần đo. */
export interface WebRtcCounters {
  at: number
  bytesReceived: number
  packetsReceived: number
  packetsLost: number
  availableIncomingKbps: number | null
}

/**
 * Ước tính băng thông cho WebRTC. Chỉ coi là thiếu băng thông khi có mất gói thật: mất > 5% thì ước tính = min(băng thông
 * trình duyệt báo, 70% bitrate đang nhận); mất < 1% coi như còn dư. Không dùng riêng availableIncomingBitrate vì ước
 * lượng của trình duyệt khởi đầu thấp rồi mới tăng dần, dễ hạ luồng nhầm ngay sau khi kết nối.
 */
export function estimateFromWebRtc(prev: WebRtcCounters, cur: WebRtcCounters): BandwidthSample {
  const seconds = (cur.at - prev.at) / 1000
  if (seconds <= 0) return { estimateKbps: null, receivedKbps: null }
  const receivedKbps = ((cur.bytesReceived - prev.bytesReceived) * 8) / 1000 / seconds
  const received = cur.packetsReceived - prev.packetsReceived
  const lost = Math.max(0, cur.packetsLost - prev.packetsLost)
  const total = received + lost
  // Chưa nhận gói nào (mới kết nối, tab ẩn): không đủ dữ liệu, không coi là thiếu băng thông
  if (total === 0) return { estimateKbps: null, receivedKbps }
  const loss = lost / total
  if (loss > 0.05) {
    const congested = receivedKbps * 0.7
    return { estimateKbps: cur.availableIncomingKbps != null ? Math.min(cur.availableIncomingKbps, congested) : congested, receivedKbps }
  }
  if (loss < 0.01) return { estimateKbps: Math.max(cur.availableIncomingKbps ?? 0, Number.MAX_SAFE_INTEGER), receivedKbps }
  return { estimateKbps: null, receivedKbps }
}
