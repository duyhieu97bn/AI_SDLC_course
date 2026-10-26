/**
 * Hạ tần số mẫu âm thanh micro (thường 44,1/48 kHz) xuống PCM 16 bit cho kênh đàm thoại (US-15, 8 kHz).
 * Mỗi mẫu ra là trung bình các mẫu vào trong khoảng của nó (lọc thông thấp đơn giản, giảm méo do chồng phổ);
 * giữ phần lẻ vị trí và mẫu thừa giữa các khối nên âm thanh không bị lệch cao độ theo thời gian.
 */
export class PcmResampler {
  private readonly ratio: number
  private rest = new Float32Array(0)
  private phase = 0

  constructor(inRate: number, outRate = 8000) {
    if (inRate < outRate) throw new Error('Tần số mẫu vào phải ≥ tần số mẫu ra')
    this.ratio = inRate / outRate
  }

  push(block: Float32Array): Int16Array {
    const buf = new Float32Array(this.rest.length + block.length)
    buf.set(this.rest)
    buf.set(block, this.rest.length)
    const out: number[] = []
    let start = this.phase
    while (Math.floor(start + this.ratio) <= buf.length) {
      const from = Math.floor(start)
      const to = Math.max(from + 1, Math.floor(start + this.ratio))
      let sum = 0
      for (let i = from; i < to; i++) sum += buf[i] ?? 0
      const v = Math.max(-1, Math.min(1, sum / (to - from)))
      out.push(Math.round(v * 0x7fff))
      start += this.ratio
    }
    const consumed = Math.floor(start)
    this.phase = start - consumed
    this.rest = buf.slice(consumed)
    return Int16Array.from(out)
  }
}
