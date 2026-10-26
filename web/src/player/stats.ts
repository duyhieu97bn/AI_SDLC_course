export interface Summary {
  count: number
  avg: number
  p95: number
  max: number
}

/** Thống kê first frame. P95 theo phương pháp nearest-rank, giống cách tính trong báo cáo PoC. */
export function summarize(values: readonly number[]): Summary | null {
  if (values.length === 0) return null
  const sorted = [...values].sort((a, b) => a - b)
  const rank = Math.ceil(0.95 * sorted.length) - 1
  const sum = sorted.reduce((a, b) => a + b, 0)
  return {
    count: sorted.length,
    avg: Math.round(sum / sorted.length),
    p95: sorted[rank] ?? 0,
    max: sorted[sorted.length - 1] ?? 0,
  }
}

export function toCsv(rows: readonly { path: string; mode: string; firstFrameMs: number | null; error: string }[]): string {
  const esc = (s: string) => `"${s.replace(/"/g, '""')}"`
  return [
    'path,mode,first_frame_ms,error',
    ...rows.map((r) => [esc(r.path), r.mode, r.firstFrameMs ?? '', esc(r.error)].join(',')),
  ].join('\n')
}
