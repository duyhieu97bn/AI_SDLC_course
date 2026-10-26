import { afterEach, describe, expect, it, vi } from 'vitest'
import { summarize, toCsv } from './stats'
import { WhepPlayer } from './WhepPlayer'
import { withToken } from './HlsFallback'

describe('summarize', () => {
  it('tính trung bình, p95 nearest-rank và max', () => {
    const values = Array.from({ length: 20 }, (_, i) => (i + 1) * 100) // 100..2000
    expect(summarize(values)).toEqual({ count: 20, avg: 1050, p95: 1900, max: 2000 })
  })

  it('trả null khi chưa có số đo', () => {
    expect(summarize([])).toBeNull()
  })
})

describe('toCsv', () => {
  it('thoát dấu ngoặc kép trong thông báo lỗi', () => {
    const csv = toCsv([{ path: 'sim-1', mode: 'webrtc', firstFrameMs: null, error: 'WHEP "401"' }])
    expect(csv.split('\n')[1]).toBe('"sim-1",webrtc,,"WHEP ""401"""')
  })
})

describe('withToken', () => {
  it('gắn token vào mọi URL playlist/segment', () => {
    expect(withToken('https://h/media/hls/sim-1/index.m3u8', 'abc')).toBe('https://h/media/hls/sim-1/index.m3u8?token=abc')
    expect(withToken('https://h/a.mp4?x=1', 'abc')).toBe('https://h/a.mp4?x=1&token=abc')
  })
})

/** RTCPeerConnection giả: đủ để kiểm tra trình tự WHEP mà không cần trình duyệt thật. */
class FakePc extends EventTarget {
  iceGatheringState: RTCIceGatheringState = 'complete'
  localDescription: RTCSessionDescriptionInit | null = null
  remoteDescription: RTCSessionDescriptionInit | null = null
  ontrack: ((e: RTCTrackEvent) => void) | null = null
  transceivers: string[] = []
  closed = false
  addTransceiver(kind: string) {
    this.transceivers.push(kind)
  }
  async createOffer() {
    return { type: 'offer' as const, sdp: 'v=0 offer' }
  }
  async setLocalDescription(d: RTCSessionDescriptionInit) {
    this.localDescription = d
  }
  async setRemoteDescription(d: RTCSessionDescriptionInit) {
    this.remoteDescription = d
  }
  close() {
    this.closed = true
  }
}

describe('WhepPlayer', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('POST offer SDP, áp answer, đo first frame và DELETE phiên khi dừng', async () => {
    vi.stubGlobal('MediaStream', class {
      addTrack() {}
    })
    const pc = new FakePc()
    const fetchFn = vi.fn(async (_url: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === 'POST') {
        return new Response('v=0 answer', { status: 201, headers: { Location: '/media/webrtc/sim-1/whep/s1' } })
      }
      return new Response(null, { status: 200 })
    })
    const video = document.createElement('video')
    // jsdom không có requestVideoFrameCallback: giả lập khung hình đầu tiên qua loadeddata
    setTimeout(() => video.dispatchEvent(new Event('loadeddata')), 10)

    const player = new WhepPlayer(video, '/media/webrtc/sim-1/whep?token=t', {
      fetchFn: fetchFn as unknown as typeof fetch,
      createPeerConnection: () => pc as unknown as RTCPeerConnection,
    })
    const result = await player.start()

    expect(pc.transceivers).toEqual(['video', 'audio'])
    const [url, init] = fetchFn.mock.calls[0]!
    expect(url).toBe('/media/webrtc/sim-1/whep?token=t')
    expect(init?.headers).toEqual({ 'Content-Type': 'application/sdp' })
    expect(init?.body).toBe('v=0 offer')
    expect(pc.remoteDescription).toEqual({ type: 'answer', sdp: 'v=0 answer' })
    expect(result.mode).toBe('webrtc')
    expect(result.firstFrameMs).toBeGreaterThanOrEqual(0)

    await player.stop()
    expect(pc.closed).toBe(true)
    expect(fetchFn.mock.calls[1]![1]?.method).toBe('DELETE')
    expect(String(fetchFn.mock.calls[1]![0])).toMatch(/\/media\/webrtc\/sim-1\/whep\/s1$/)
  })

  it('báo lỗi khi máy chủ từ chối (token sai)', async () => {
    vi.stubGlobal('MediaStream', class {
      addTrack() {}
    })
    const player = new WhepPlayer(document.createElement('video'), '/w', {
      fetchFn: (async () => new Response('', { status: 401 })) as unknown as typeof fetch,
      createPeerConnection: () => new FakePc() as unknown as RTCPeerConnection,
    })
    await expect(player.start()).rejects.toThrow('WHEP trả về 401')
  })
})
