import { describe, expect, it } from 'vitest'
import { addCamera, corrections, maxDriftS } from './SyncController'

describe('SyncController (US-19)', () => {
  it('kéo các camera lệch quá 0,4 giây về cùng thời điểm tuyệt đối với camera chuẩn', () => {
    const t0 = Date.UTC(2026, 9, 25, 14, 0, 0)
    // Camera 2 bắt đầu phiên sớm hơn 2 giây (nhảy tới đoạn gần nhất) nên currentTime phải lớn hơn 2 giây
    const streams = [
      { startMs: t0, currentTime: 10 },
      { startMs: t0 - 2000, currentTime: 12.1 },
      { startMs: t0, currentTime: 9 },
      { startMs: t0 + 60_000, currentTime: 0 },
    ]
    const c = corrections(streams)
    expect(c[0]).toBeNull()
    expect(c[1]).toBeNull() // lệch 0,1 giây: giữ nguyên
    expect(c[2]).toBe(10)
    expect(c[3]).toBeNull() // mốc chuẩn trước đoạn của camera 4: không chỉnh
    expect(maxDriftS(streams.slice(0, 3))).toBeCloseTo(1)
  })

  it('tối đa 4 camera', () => {
    let list: string[] = []
    for (const id of ['a', 'b', 'c', 'd']) list = addCamera(list, id).list
    const r = addCamera(list, 'e')
    expect(r.ok).toBe(false)
    expect(r.list).toHaveLength(4)
    expect(addCamera(list, 'a').ok).toBe(true)
  })
})
