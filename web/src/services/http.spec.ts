import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, configureAuth, http } from './http'
import vi_ from '@/i18n/vi.json'
import en from '@/i18n/en.json'

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

describe('http', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('gặp 401 thì làm mới phiên đúng một lần cho mọi request đang chờ, rồi gọi lại', async () => {
    let token = 'old'
    const refresh = vi.fn(async () => {
      await new Promise((r) => setTimeout(r, 10))
      token = 'new'
      return true
    })
    configureAuth({ getToken: () => token, refresh, onSessionExpired: vi.fn() })
    vi.stubGlobal(
      'fetch',
      vi.fn(async (_url: string, init: RequestInit) => {
        const auth = (init.headers as Record<string, string>).Authorization
        return auth === 'Bearer new' ? json(200, { ok: true }) : json(401, { code: 'UNAUTHENTICATED' })
      }),
    )
    const results = await Promise.all([http.get('/a'), http.get('/b'), http.get('/c')])
    expect(results).toEqual([{ ok: true }, { ok: true }, { ok: true }])
    expect(refresh).toHaveBeenCalledTimes(1)
  })

  it('làm mới thất bại thì báo hết phiên và ném ApiError có mã', async () => {
    const expired = vi.fn()
    configureAuth({ getToken: () => 't', refresh: async () => false, onSessionExpired: expired })
    vi.stubGlobal('fetch', vi.fn(async () => json(401, { code: 'SESSION_EXPIRED', detail: 'hết phiên' })))
    await expect(http.get('/x')).rejects.toMatchObject({ status: 401, code: 'SESSION_EXPIRED' })
    expect(expired).toHaveBeenCalled()
  })

  it('đọc lỗi RFC 7807 kèm trường bổ sung', async () => {
    configureAuth({ getToken: () => 't', refresh: async () => false, onSessionExpired: vi.fn() })
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => json(403, { code: 'ROLE_EXCEEDS_OWN_PERMISSIONS', detail: 'x', permission: 'TALK_AUDIO' })),
    )
    const e = await http.post('/users', {}).catch((x: unknown) => x)
    expect(e).toBeInstanceOf(ApiError)
    expect((e as ApiError).details.permission).toBe('TALK_AUDIO')
  })
})

describe('i18n (US-41)', () => {
  function keys(o: object, prefix = ''): string[] {
    return Object.entries(o).flatMap(([k, v]) =>
      typeof v === 'object' && v !== null ? keys(v as object, `${prefix}${k}.`) : [`${prefix}${k}`],
    )
  }

  it('tiếng Việt và tiếng Anh có cùng bộ khóa', () => {
    expect(keys(en).sort()).toEqual(keys(vi_).sort())
  })
})
