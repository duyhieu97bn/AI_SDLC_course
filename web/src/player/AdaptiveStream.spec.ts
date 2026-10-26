import { describe, expect, it } from 'vitest'
import { AdaptiveController, estimateFromWebRtc } from './AdaptiveStream'

describe('AdaptiveController (US-11)', () => {
  it('băng thông dưới bitrate luồng chính liên tục 5 giây thì chuyển luồng phụ', () => {
    const c = new AdaptiveController('MAIN', 4000)
    expect(c.sample({ estimateKbps: 3000, receivedKbps: 3000 }, 0)).toBeNull()
    expect(c.sample({ estimateKbps: 3000, receivedKbps: 3000 }, 4000)).toBeNull()
    expect(c.sample({ estimateKbps: 3000, receivedKbps: 3000 }, 5000)).toBe('SUB')
    expect(c.stream).toBe('SUB')
  })

  it('sụt ngắn dưới 5 giây thì không chuyển', () => {
    const c = new AdaptiveController('MAIN', 4000)
    c.sample({ estimateKbps: 3000, receivedKbps: 3000 }, 0)
    c.sample({ estimateKbps: 9000, receivedKbps: 4000 }, 3000)
    expect(c.sample({ estimateKbps: 3000, receivedKbps: 3000 }, 6000)).toBeNull()
  })

  it('quay lại luồng chính khi băng thông ≥ 1,5 lần trong 30 giây', () => {
    const c = new AdaptiveController('SUB', 4000)
    expect(c.sample({ estimateKbps: 6000, receivedKbps: 500 }, 0)).toBeNull()
    expect(c.sample({ estimateKbps: 6000, receivedKbps: 500 }, 29_000)).toBeNull()
    expect(c.sample({ estimateKbps: 6000, receivedKbps: 500 }, 30_000)).toBe('MAIN')
  })

  it('băng thông 1,4 lần thì vẫn ở luồng phụ', () => {
    const c = new AdaptiveController('SUB', 4000)
    c.sample({ estimateKbps: 5600, receivedKbps: 500 }, 0)
    expect(c.sample({ estimateKbps: 5600, receivedKbps: 500 }, 60_000)).toBeNull()
  })

  it('ước tính WebRTC từ mất gói', () => {
    const base = { at: 0, bytesReceived: 0, packetsReceived: 0, packetsLost: 0, availableIncomingKbps: null }
    const congested = estimateFromWebRtc(base, { at: 1000, bytesReceived: 500_000, packetsReceived: 90, packetsLost: 10, availableIncomingKbps: null })
    expect(congested.receivedKbps).toBe(4000)
    expect(congested.estimateKbps).toBeCloseTo(2800)
    const clean = estimateFromWebRtc(base, { at: 1000, bytesReceived: 500_000, packetsReceived: 100, packetsLost: 0, availableIncomingKbps: null })
    expect(clean.estimateKbps).toBeGreaterThan(1_000_000)
    const idle = estimateFromWebRtc(base, { at: 1000, bytesReceived: 0, packetsReceived: 0, packetsLost: 0, availableIncomingKbps: null })
    expect(idle.estimateKbps).toBeNull()
    // Trình duyệt báo băng thông thấp nhưng không mất gói (đang khởi động ước lượng): không hạ luồng
    const rampUp = estimateFromWebRtc(base, { at: 1000, bytesReceived: 500_000, packetsReceived: 100, packetsLost: 0, availableIncomingKbps: 300 })
    expect(rampUp.estimateKbps).toBeGreaterThan(1_000_000)
    const reported = estimateFromWebRtc(base, { at: 1000, bytesReceived: 500_000, packetsReceived: 80, packetsLost: 20, availableIncomingKbps: 2500 })
    expect(reported.estimateKbps).toBe(2500)
  })
})
