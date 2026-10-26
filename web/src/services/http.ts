/**
 * Gọi API Core qua /api/v1 (US-30.3). Access token chỉ giữ trong bộ nhớ; refresh token là cookie HttpOnly
 * do trình duyệt tự gửi tới /api/v1/auth. Gặp 401 thì làm mới phiên một lần (dùng chung cho mọi request đang chờ)
 * rồi gọi lại; làm mới thất bại thì báo phiên hết hạn để chuyển về trang đăng nhập.
 */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly details: Record<string, unknown> = {},
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

interface TokenHooks {
  getToken: () => string | null
  refresh: () => Promise<boolean>
  onSessionExpired: () => void
}

let hooks: TokenHooks = {
  getToken: () => null,
  refresh: async () => false,
  onSessionExpired: () => undefined,
}
let refreshing: Promise<boolean> | null = null

export function configureAuth(h: TokenHooks) {
  hooks = h
}

function refreshOnce(): Promise<boolean> {
  refreshing ??= hooks.refresh().finally(() => {
    refreshing = null
  })
  return refreshing
}

export interface RequestOptions {
  /** Không tự làm mới phiên khi 401 (dùng cho chính các endpoint /auth) */
  noRefresh?: boolean
  signal?: AbortSignal
}

export async function request<T>(method: string, path: string, body?: unknown, opts: RequestOptions = {}): Promise<T> {
  const send = () => {
    const headers: Record<string, string> = { Accept: 'application/json, application/problem+json' }
    const token = hooks.getToken()
    if (token) headers.Authorization = `Bearer ${token}`
    // FormData (tải tệp lên): để trình duyệt tự đặt Content-Type multipart kèm boundary
    const form = body instanceof FormData
    if (body !== undefined && !form) headers['Content-Type'] = 'application/json'
    return fetch(`/api/v1${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : form ? body : JSON.stringify(body),
      credentials: 'same-origin',
      signal: opts.signal,
    })
  }

  let res = await send()
  if (res.status === 401 && !opts.noRefresh) {
    if (await refreshOnce()) {
      res = await send()
    }
    if (res.status === 401) {
      hooks.onSessionExpired()
    }
  }
  if (!res.ok) {
    throw await toError(res)
  }
  if (res.status === 204 || res.headers.get('Content-Length') === '0') {
    return undefined as T
  }
  const type = res.headers.get('Content-Type') ?? ''
  return (type.includes('json') ? await res.json() : await res.text()) as T
}

async function toError(res: Response): Promise<ApiError> {
  try {
    const p = (await res.json()) as Record<string, unknown>
    const { code, detail, ...rest } = p
    return new ApiError(res.status, String(code ?? `HTTP_${res.status}`), String(detail ?? res.statusText), rest)
  } catch {
    return new ApiError(res.status, `HTTP_${res.status}`, res.statusText)
  }
}

export const http = {
  get: <T>(path: string, opts?: RequestOptions) => request<T>('GET', path, undefined, opts),
  post: <T>(path: string, body?: unknown, opts?: RequestOptions) => request<T>('POST', path, body ?? {}, opts),
  put: <T>(path: string, body?: unknown) => request<T>('PUT', path, body ?? {}),
  patch: <T>(path: string, body?: unknown) => request<T>('PATCH', path, body ?? {}),
  delete: <T>(path: string) => request<T>('DELETE', path),
}

/** Tải file từ API có xác thực (ví dụ xuất CSV), lưu với tên máy chủ gợi ý. */
export async function download(path: string, fallbackName: string) {
  const res = await fetch(`/api/v1${path}`, {
    headers: { Authorization: `Bearer ${hooks.getToken() ?? ''}` },
    credentials: 'same-origin',
  })
  if (!res.ok) throw await toError(res)
  const name = /filename="([^"]+)"/.exec(res.headers.get('Content-Disposition') ?? '')?.[1] ?? fallbackName
  const a = document.createElement('a')
  a.href = URL.createObjectURL(await res.blob())
  a.download = name
  a.click()
  URL.revokeObjectURL(a.href)
}

/** Access token hiện tại (WebSocket STOMP gửi trong frame CONNECT). */
/** Tải tài nguyên cần access token (ví dụ ảnh sự kiện) thành blob URL để gán cho <img>. Người gọi tự revoke. */
export async function blobUrl(path: string): Promise<string> {
  const res = await fetch(path, { headers: { Authorization: `Bearer ${hooks.getToken() ?? ''}` }, credentials: 'same-origin' })
  if (!res.ok) throw await toError(res)
  return URL.createObjectURL(await res.blob())
}

export function currentToken(): string | null {
  return hooks.getToken()
}

/** Tạo query string, bỏ qua giá trị rỗng. */
export function qs(params: Record<string, string | number | null | undefined>): string {
  const s = new URLSearchParams()
  Object.entries(params).forEach(([k, v]) => {
    if (v !== null && v !== undefined && v !== '') s.set(k, String(v))
  })
  const str = s.toString()
  return str ? `?${str}` : ''
}
