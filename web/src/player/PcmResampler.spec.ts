import { describe, expect, it } from 'vitest'
import { PcmResampler } from './PcmResampler'

function sine(rate: number, seconds: number, freq: number, amp = 0.5) {
  return Float32Array.from({ length: Math.round(rate * seconds) }, (_, i) => amp * Math.sin((2 * Math.PI * freq * i) / rate))
}

describe('PcmResampler (US-15)', () => {
  it('48 kHz → 8 kHz: đúng số mẫu khi đẩy theo từng khối, không lệch cao độ', () => {
    const r = new PcmResampler(48000)
    const input = sine(48000, 1, 440)
    let total = 0
    for (let i = 0; i < input.length; i += 2048) total += r.push(input.subarray(i, i + 2048)).length
    expect(total).toBe(8000)
  })

  it('44,1 kHz (tỉ lệ lẻ) → 8 kHz: sai số tối đa 1 mẫu mỗi giây', () => {
    const r = new PcmResampler(44100)
    const input = sine(44100, 3, 300)
    let total = 0
    for (let i = 0; i < input.length; i += 4096) total += r.push(input.subarray(i, i + 4096)).length
    expect(Math.abs(total - 24000)).toBeLessThanOrEqual(1)
  })

  it('giữ biên độ âm tần thấp, chặn tràn và đổi sang int16', () => {
    const r = new PcmResampler(16000)
    const out = r.push(sine(16000, 0.5, 200, 0.5))
    expect(Math.max(...out)).toBeGreaterThan(0.45 * 0x7fff)
    expect(Math.max(...out)).toBeLessThanOrEqual(0.5 * 0x7fff + 1)
    expect(Array.from(r.push(Float32Array.from([3, 3, -3, -3])))).toEqual([0x7fff, -0x7fff])
  })
})
