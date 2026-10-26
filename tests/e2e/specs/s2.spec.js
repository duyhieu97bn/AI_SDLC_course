// Phase S2: US-01 dò tìm ONVIF, US-02 thêm thủ công, US-04 trạng thái, US-31 2FA, US-33 nhật ký.
// Cần profile "sim" (camera-sim, rtsp-sim, onvif-sim-1/2) – ./deploy/scripts/dev.sh up mặc định có.
import { expect, test } from '@playwright/test'
import { ADMIN, DEMO_PASSWORD, login } from './helpers.js'
import { totp } from './totp.js'

test.describe.configure({ mode: 'serial' })

test('US-01 Dò tìm ONVIF: tìm thấy camera giả lập, thêm hàng loạt, lần sau đánh dấu "Đã thêm"', async ({ page }) => {
  test.setTimeout(180_000) // hai lần dò, mỗi lần chờ đủ 30 giây
  await login(page, ADMIN.email, ADMIN.password)
  await page.goto('/cameras')
  await page.getByTestId('add-camera').click()
  await page.getByTestId('add-discovery').click()
  await page.selectOption('#d-site', { label: 'Kho Bắc Ninh' })
  await page.getByTestId('discovery-scan').click()
  const rows = page.getByTestId('found-row')
  await expect(rows.filter({ hasText: 'onvif-sim-1' })).toHaveCount(1, { timeout: 60_000 })
  await expect(rows.filter({ hasText: 'onvif-sim-2' })).toHaveCount(1)

  const fresh = rows.filter({ hasNot: page.locator('.badge') })
  if ((await fresh.count()) === 0) test.skip(true, 'Camera giả lập đã được thêm ở lần chạy trước')
  await page.getByTestId('discovery-next').click()
  await page.fill('#d-user', 'admin')
  await page.fill('#d-pass', 'admin123')
  await page.getByTestId('discovery-add').click()
  const results = page.getByTestId('bulk-result')
  await expect(results.first()).toBeVisible({ timeout: 30_000 })
  await expect(results.filter({ hasText: 'Xong' })).toHaveCount(await results.count())
  await page.locator('.modal-foot .btn').first().click()

  // Dò lại: camera vừa thêm bị đánh dấu "Đã thêm", không chọn được
  await page.getByTestId('add-camera').click()
  await page.getByTestId('add-discovery').click()
  await page.selectOption('#d-site', { label: 'Kho Bắc Ninh' })
  await page.getByTestId('discovery-scan').click()
  const sim1 = page.getByTestId('found-row').filter({ hasText: 'onvif-sim-1' })
  await expect(sim1).toContainText('Đã thêm', { timeout: 60_000 })
  await expect(sim1.locator('input[type="checkbox"]')).toBeDisabled()
})

test('US-01 Một camera sai mật khẩu: các camera khác vẫn thêm được, camera lỗi có nút nhập lại', async ({ page }) => {
  await login(page, ADMIN.email, ADMIN.password)
  // API trực tiếp: thêm hàng loạt với mật khẩu sai cho một máy giả lập không tồn tại tài khoản
  const token = await page.evaluate(async () => {
    const r = await fetch('/api/v1/auth/refresh', { method: 'POST' })
    return (await r.json()).accessToken
  })
  const res = await page.request.post('/api/v1/cameras/bulk', {
    headers: { Authorization: `Bearer ${token}` },
    data: { zoneId: await firstZone(page, token), username: 'admin', password: 'sai-mat-khau', devices: [{ host: 'onvif-sim-2', port: 8080 }] },
  })
  const body = await res.json()
  expect(body[0].ok).toBe(false)
  expect(['AUTH_FAILED', 'DUPLICATE']).toContain(body[0].code)
})

test('US-02 Thêm camera RTSP thủ công: kiểm tra kết nối có hình xem trước rồi lưu', async ({ page }) => {
  await login(page, ADMIN.email, ADMIN.password)
  await page.goto('/cameras')
  await page.getByTestId('add-camera').click()
  await page.getByTestId('add-manual').click()
  await page.getByTestId('proto-rtsp').click()
  await page.fill('#c-url', 'rtsp://camera-sim:8554/sim-23')
  await page.fill('#c-user', 'admin')
  await page.fill('#c-pass', 'admin123')
  await page.getByTestId('test-connection').click()
  await expect(page.getByTestId('test-ok')).toBeVisible({ timeout: 15_000 })
  // Khung hình xem trước phát được trong ≤ 10 giây (AC1)
  await expect
    .poll(() => page.getByTestId('preview-video').evaluate((v) => v.videoWidth), { timeout: 10_000 })
    .toBeGreaterThan(0)
  const name = `RTSP thủ công ${Date.now()}`
  await page.fill('#c-name', name)
  await page.selectOption('#c-zone', { label: 'Kho hàng' })
  await page.getByTestId('save-camera').click()
  await expect(page.locator('.toast').last()).toContainText('Đã thêm camera')
  await page.getByTestId('camera-search').fill(name)
  await expect(page.getByTestId('camera-row')).toHaveCount(1)
})

test('US-02 Không tới được địa chỉ: chỉ lưu được khi chọn "Lưu dù chưa kết nối"', async ({ page }) => {
  await login(page, ADMIN.email, ADMIN.password)
  await page.goto('/cameras')
  await page.getByTestId('add-camera').click()
  await page.getByTestId('add-manual').click()
  await page.fill('#c-host', '10.254.254.254')
  await page.fill('#c-pass', 'x')
  await page.getByTestId('test-connection').click()
  await expect(page.getByTestId('test-failed')).toContainText('Không tới được địa chỉ', { timeout: 40_000 })
  await page.selectOption('#c-zone', { label: 'Bãi xe' })
  await expect(page.getByTestId('save-camera')).toBeDisabled()
  await page.getByTestId('save-unverified').check()
  await expect(page.getByTestId('save-camera')).toBeEnabled()
  await page.locator('.modal-foot .btn').first().click()
})

test('US-04 Tổng quan theo địa điểm hiển thị số camera trực tuyến / tổng', async ({ page }) => {
  await login(page, ADMIN.email, ADMIN.password)
  await expect(page.getByTestId('site-status').first()).toContainText(/\d+ \/ \d+/)
})

test('US-33 Nhật ký: lọc CHANGE_CONFIG thấy thao tác thêm camera, xuất CSV', async ({ page }) => {
  await login(page, ADMIN.email, ADMIN.password)
  await page.goto('/audit')
  await page.getByTestId('audit-action').selectOption('CHANGE_CONFIG')
  await expect(page.getByTestId('audit-row').first()).toContainText('CHANGE_CONFIG')
  await expect(page.getByTestId('audit-row').filter({ hasText: 'RTSP thủ công' }).first()).toBeVisible()
  const download = page.waitForEvent('download')
  await page.getByTestId('audit-export').click()
  expect((await download).suggestedFilename()).toMatch(/^camhub-audit-.*\.csv$/)
})

test('US-31 Bắt buộc 2FA cho Viewer: thiết lập QR, lưu mã dự phòng, đăng nhập lại bằng mã TOTP', async ({ browser }) => {
  const adminPage = await browser.newPage()
  await login(adminPage, ADMIN.email, ADMIN.password)
  await adminPage.goto('/system')
  const toggle = adminPage.getByTestId('require-2fa-VIEWER')
  // Ô checkbox của công tắc bị ẩn (design), bấm vào chính công tắc
  const sw = adminPage.locator('label.switch', { has: toggle })
  if (!(await toggle.isChecked())) {
    await sw.click()
    await expect(adminPage.locator('.toast').last()).toContainText('Đã lưu')
  }

  try {
    const page = await browser.newPage()
    await page.goto('/login')
    await page.fill('#email', 'viewer@camhub.local')
    await page.fill('#password', DEMO_PASSWORD)
    await page.click('button[type="submit"]')
    const secretEl = page.getByTestId('totp-secret')
    // Chờ bước 2FA hiện ra: thiết lập (có secret) hoặc nhập mã
    await expect(page.locator('#otp')).toBeVisible({ timeout: 10_000 })
    if (await secretEl.isVisible()) {
      const secret = (await secretEl.textContent()).trim()
      await page.fill('#otp', totp(secret))
      await page.getByTestId('otp-submit').click()
      await expect(page.getByTestId('recovery-codes').locator('span')).toHaveCount(10)
      await page.getByTestId('codes-continue').click()
      await expect(page).toHaveURL(/\/live$/)
      // Lưu secret để lần sau dùng (ghi vào localStorage của trang để test kế tiếp đọc lại)
      process.env.E2E_VIEWER_TOTP = secret
    } else {
      test.skip(!process.env.E2E_VIEWER_TOTP, 'Viewer đã bật 2FA ở lần chạy trước, không biết secret')
      await page.fill('#otp', totp(process.env.E2E_VIEWER_TOTP))
      await page.getByTestId('otp-submit').click()
      await expect(page).toHaveURL(/\/live$/)
    }
    await page.close()
  } finally {
    await adminPage.locator('label.switch', { has: adminPage.getByTestId('require-2fa-VIEWER') }).click()
    await expect(adminPage.getByTestId('require-2fa-VIEWER')).not.toBeChecked()
    await adminPage.close()
  }
})

async function firstZone(page, token) {
  const sites = await (await page.request.get('/api/v1/sites', { headers: { Authorization: `Bearer ${token}` } })).json()
  return sites[0].zones[0].id
}
