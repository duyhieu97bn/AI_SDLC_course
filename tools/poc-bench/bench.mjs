// Đo first frame tự động bằng Chrome headless (TS-03.6).
// Mở trang PoC qua Nginx, phát N luồng, chờ mọi ô xong, in kết quả JSON một dòng mỗi lượt.
// Chạy trong container cùng mạng backend với MediaMTX (xem run.sh), nên ICE dùng IP nội bộ của Docker.
import { chromium } from 'playwright'

const base = process.env.BENCH_URL ?? 'https://nginx'
const counts = (process.env.BENCH_COUNTS ?? '1,4,9,16,20').split(',').map(Number)
const mode = process.env.BENCH_MODE ?? 'webrtc'
const rounds = Number(process.env.BENCH_ROUNDS ?? '3')
const timeoutMs = Number(process.env.BENCH_TIMEOUT_MS ?? '30000')

const browser = await chromium.launch({
  channel: 'chrome', // Chrome có bộ giải mã H.264; Chromium của Playwright thì không
  args: ['--autoplay-policy=no-user-gesture-required', '--ignore-certificate-errors'],
})
const context = await browser.newContext({ ignoreHTTPSErrors: true, viewport: { width: 1600, height: 1000 } })

for (const n of counts) {
  for (let round = 1; round <= rounds; round++) {
    const page = await context.newPage()
    await page.goto(`${base}/poc/player`, { waitUntil: 'networkidle' })
    await page.fill('#count', String(n))
    await page.selectOption('#mode', mode)
    await page.click('[data-testid="start"]')
    await page.waitForFunction(
      (expected) => {
        const tiles = [...document.querySelectorAll('[data-testid="tile"]')]
        return tiles.length === expected && tiles.every((t) => t.dataset.state !== 'connecting')
      },
      n,
      { timeout: timeoutMs },
    ).catch(() => undefined)
    const tiles = await page.$$eval('[data-testid="tile"]', (els) =>
      els.map((t) => ({
        path: t.dataset.path,
        state: t.dataset.state,
        mode: t.dataset.mode,
        ms: t.dataset.firstFrameMs ? Number(t.dataset.firstFrameMs) : null,
        error: t.dataset.error || undefined,
      })),
    )
    const ok = tiles.filter((t) => t.ms !== null).map((t) => t.ms).sort((a, b) => a - b)
    const p95 = ok.length ? ok[Math.ceil(0.95 * ok.length) - 1] : null
    const avg = ok.length ? Math.round(ok.reduce((a, b) => a + b, 0) / ok.length) : null
    console.log(JSON.stringify({ streams: n, round, mode, ok: ok.length, failed: n - ok.length, avg, p95, max: ok.at(-1) ?? null,
      errors: tiles.filter((t) => t.error).map((t) => `${t.path}: ${t.error}`).slice(0, 3) }))
    // Giữ luồng 10 giây để quan sát độ ổn định, rồi đóng trang (player tự DELETE phiên WHEP)
    await page.waitForTimeout(10000)
    await page.close()
  }
}
await browser.close()
