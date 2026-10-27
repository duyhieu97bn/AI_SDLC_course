// Đo first frame trên trang Xem trực tiếp thật (NFR-01, TS-08.2): đăng nhập, chọn lưới N, thêm N camera trực tuyến
// đầu tiên trên cây, ghi thời điểm từng ô có khung hình đầu tiên (video.videoWidth > 0) tính từ lúc bấm camera đầu.
// Lưới 9 và 16 dùng luồng phụ như người dùng thật. Mỗi dòng in ra là một lượt (JSON).
import { chromium } from 'playwright'

const base = process.env.FF_URL ?? 'https://nginx'
const counts = (process.env.FF_COUNTS ?? '1,4,9,16').split(',').map(Number)
const rounds = Number(process.env.FF_ROUNDS ?? '3')
const timeoutMs = 20_000
const SIM_CAMERA = /^\s*(Bãi xe|Cổng chính|Kho hàng|Quầy giao dịch|Sảnh|Mặt tiền|Kho cửa hàng) \d+\s*$/

const browser = await chromium.launch({ channel: 'chrome', args: ['--autoplay-policy=no-user-gesture-required'] })
const context = await browser.newContext({ ignoreHTTPSErrors: true, viewport: { width: 1600, height: 1000 } })
// Một trang duy nhất (đăng nhập rồi dùng tiếp) như người dùng thật
const page = await context.newPage()
await page.goto(`${base}/login`)
await page.fill('#email', process.env.FF_EMAIL ?? 'admin@camhub.local')
await page.fill('#password', process.env.FF_PASSWORD)
await page.click('button[type="submit"]')
await page.waitForURL((u) => !u.pathname.startsWith('/login'), { timeout: 15_000 })

for (const n of counts) {
  for (let round = 1; round <= rounds; round++) {
    await page.goto(`${base}/live`)
    await page.getByTestId(`grid-${n}`).click()
    const tiles = page.getByTestId('video-tile')
    // Bố cục dùng gần nhất tự mở: bỏ hết ô để bắt đầu từ lưới trống
    while ((await tiles.count()) > 0) {
      await tiles.first().click()
      await page.getByTestId('remove-tile').click()
    }
    // Chỉ camera dữ liệu mẫu nhận luồng từ bộ giả lập (camera nhập thử/chưa xác minh có thể trỏ địa chỉ không tồn tại)
    const cams = page.locator('[data-testid="tree-camera"]:not(.off)').filter({ hasText: SIM_CAMERA })
    await page.evaluate(() => {
      window.__t0 = performance.now()
      window.__ff = {}
      const poll = () => {
        document.querySelectorAll('[data-testid="video-tile"]').forEach((t, i) => {
          const v = t.querySelector('video')
          if (v && v.videoWidth > 0 && window.__ff[i] === undefined) window.__ff[i] = Math.round(performance.now() - window.__t0)
        })
        if (Object.keys(window.__ff).length < 16) requestAnimationFrame(poll)
      }
      requestAnimationFrame(poll)
    })
    for (let i = 0; i < n; i++) await cams.nth(i).click()
    await page.waitForFunction((expected) => Object.keys(window.__ff).length >= expected, n, { timeout: timeoutMs })
      .catch(() => undefined)
    const ff = Object.values(await page.evaluate(() => window.__ff)).sort((a, b) => a - b)
    if (process.env.FF_DEBUG) {
      console.log(page.url(), await tiles.evaluateAll((els) =>
        els.map((e) => [e.innerText.split('\n')[0], e.dataset.state, e.dataset.stream, e.querySelector('video')?.videoWidth])))
    }
    const p95 = ff.length ? ff[Math.ceil(0.95 * ff.length) - 1] : null
    const avg = ff.length ? Math.round(ff.reduce((a, b) => a + b, 0) / ff.length) : null
    const streams = await tiles.evaluateAll((els) => [...new Set(els.map((e) => e.dataset.stream))])
    console.log(JSON.stringify({ tiles: n, round, streams, ok: ff.length, failed: n - ff.length, avg, p95, max: ff.at(-1) ?? null }))
    await page.waitForTimeout(5_000)
  }
}
await browser.close()
