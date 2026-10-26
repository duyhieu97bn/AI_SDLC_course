/**
 * Đồng bộ phát lại nhiều camera (US-19): tối đa 4 camera cùng phát theo một mốc thời gian; camera đầu tiên làm chuẩn,
 * các camera khác được kéo về cùng thời điểm tuyệt đối khi lệch quá ngưỡng (yêu cầu lệch ≤ 1 giây).
 */
export const MAX_SYNC_CAMERAS = 4
export const DRIFT_TOLERANCE_S = 0.4

/** Một luồng: thời điểm tuyệt đối (ms) ứng với currentTime = 0 và vị trí hiện tại (giây). */
export interface SyncStream {
  startMs: number
  currentTime: number
}

/** Thời điểm tuyệt đối (ms) đang phát của một luồng. */
export function absoluteMs(s: SyncStream): number {
  return s.startMs + s.currentTime * 1000
}

/**
 * Trả về currentTime mới (giây) cho từng luồng để khớp luồng chuẩn (chỉ số 0); null nếu luồng đó không cần chỉnh
 * (lệch ≤ ngưỡng) hoặc mốc chuẩn nằm ngoài đoạn của luồng (camera không có video lúc đó).
 */
export function corrections(streams: readonly SyncStream[], toleranceS = DRIFT_TOLERANCE_S): (number | null)[] {
  if (streams.length === 0) return []
  const masterMs = absoluteMs(streams[0]!)
  return streams.map((s, i) => {
    if (i === 0) return null
    const target = (masterMs - s.startMs) / 1000
    if (target < 0) return null
    return Math.abs(target - s.currentTime) > toleranceS ? target : null
  })
}

/** Độ lệch lớn nhất (giây) giữa các luồng so với luồng chuẩn. */
export function maxDriftS(streams: readonly SyncStream[]): number {
  if (streams.length < 2) return 0
  const masterMs = absoluteMs(streams[0]!)
  return Math.max(...streams.slice(1).map((s) => Math.abs(absoluteMs(s) - masterMs) / 1000))
}

/** Thêm camera vào nhóm đồng bộ; quá 4 camera thì từ chối (US-19 AC2). */
export function addCamera(selected: readonly string[], id: string): { ok: boolean; list: string[] } {
  if (selected.includes(id)) return { ok: true, list: [...selected] }
  if (selected.length >= MAX_SYNC_CAMERAS) return { ok: false, list: [...selected] }
  return { ok: true, list: [...selected, id] }
}
