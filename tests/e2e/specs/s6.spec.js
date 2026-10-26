// Phase S6: US-06 bộ kết nối địa điểm, US-03 đầu ghi NVR, US-28 đổi mật khẩu/khởi động lại, US-14 chụp ảnh/phóng to số.
// Đường hầm thật (TS-06, chỉ ra cổng 443) kiểm theo tests/integration/tunnel_test.md với profile "site";
// ở đây Connector được giả lập bằng chính API của nó (kích hoạt + heartbeat) để kiểm giao diện và realtime.
// US-11 (tự chuyển luồng) kiểm bằng unit test web/src/player/AdaptiveStream.spec.ts.
import { expect, test } from '@playwright/test'
import { ADMIN, login } from './helpers.js'

test.describe.configure({ mode: 'serial' })
const suffix = Date.now().toString(36)

test('US-06 Tạo bộ kết nối: mã kích hoạt + lệnh cài; Connector kích hoạt và heartbeat → "Trực tuyến" ngay trên giao diện', async ({ page, request }) => {
  await login(page, ADMIN.email, ADMIN.password)
  // Địa điểm mới cho bộ kết nối
  await page.goto('/cameras')
  await page.getByTestId('add-site').click()
  await page.locator('.modal input').fill(`Chi nhánh Đà Nẵng ${suffix}`)
  await page.locator('.modal input').press('Enter')
  await expect(page.locator('.modal')).toHaveCount(0)

  await page.goto('/system')
  await page.getByTestId('connector-create').click()
  await page.getByTestId('connector-site').selectOption({ label: `Chi nhánh Đà Nẵng ${suffix}` })
  await page.getByTestId('connector-save').click()
  const code = (await page.getByTestId('activation-code').textContent()).trim()
  expect(code).toMatch(/^[A-Z2-9]{4}-[A-Z2-9]{4}$/)
  await expect(page.getByTestId('install-command')).toContainText(code)
  await page.getByRole('button', { name: 'Đóng', exact: true }).last().click()
  const row = page.getByTestId('connector-row').filter({ hasText: `Chi nhánh Đà Nẵng ${suffix}` })
  await expect(row.getByTestId('connector-status')).toHaveText('Chờ kích hoạt')

  // Connector (giả lập) kích hoạt bằng mã rồi gửi heartbeat
  const act = await request.post('/api/v1/connector-agent/activate', { data: { code, hostname: 'dn-box', version: '0.1.0' } })
  expect(act.status()).toBe(200)
  const a = await act.json()
  const basic = Buffer.from(`${a.connectorId}:${a.secret}`).toString('base64')
  const hb = await request.post('/api/v1/connector-agent/heartbeat', {
    headers: { Authorization: `Basic ${basic}` },
    data: { version: '0.1.0', uptimeSeconds: 5, tunnelUp: true, rxBytes: 0, txBytes: 0, rxKbps: 250, txKbps: 40 },
  })
  expect(hb.status()).toBe(200)
  expect((await hb.json()).config.socksPort).toBeGreaterThanOrEqual(21000)
  await expect(row.getByTestId('connector-status')).toHaveText('Trực tuyến', { timeout: 10_000 })

  // Mã đã dùng không kích hoạt lại được
  const again = await request.post('/api/v1/connector-agent/activate', { data: { code } })
  expect([404, 409]).toContain(again.status())

  // Thu hồi để không ảnh hưởng lần chạy sau
  page.once('dialog', (d) => d.accept())
  await row.getByRole('button', { name: 'Thu hồi' }).click()
  await expect(row).toHaveCount(0)
})

test('US-03 Thêm đầu ghi 16 kênh, chọn 3 kênh có tín hiệu → 3 camera tên theo kênh', async ({ page }) => {
  await login(page, ADMIN.email, ADMIN.password)
  await page.goto('/cameras')
  await page.getByTestId('add-camera').click()
  await page.getByTestId('add-recorder').click()
  const zone = page.getByTestId('rec-zone')
  await zone.selectOption({ index: 0 })
  await page.locator('#rec-name').fill(`NVR ${suffix}`)
  await page.getByTestId('rec-host').fill('onvif-nvr-1')
  await page.getByTestId('rec-port').fill('8080')
  await page.getByTestId('rec-user').fill('admin')
  await page.getByTestId('rec-pass').fill('admin123')
  await page.getByTestId('rec-read').click()
  await expect(page.getByTestId('rec-channel')).toHaveCount(16, { timeout: 20_000 })
  for (const n of [1, 2, 3]) await expect(page.getByTestId(`rec-ch-${n}`)).toBeChecked()
  await expect(page.getByTestId('rec-ch-4')).toBeDisabled()
  await expect(page.getByTestId('rec-save')).toContainText('3')
  await page.getByTestId('rec-save').click()

  await page.getByTestId('camera-search').fill('Kênh 0')
  await expect
    .poll(async () => (await page.getByTestId('camera-link').allTextContents()).filter((n) => /Kênh 0[123]/.test(n)).length, {
      timeout: 15_000,
    })
    .toBeGreaterThanOrEqual(3)
})

test('US-28 Đổi mật khẩu 2 camera ONVIF: kết quả từng camera; khởi động lại có xác nhận và theo dõi', async ({ page }) => {
  await login(page, ADMIN.email, ADMIN.password)
  await page.goto('/cameras')
  await page.getByTestId('camera-search').fill('onvif-sim')
  await expect
    .poll(async () => {
      const names = await page.getByTestId('camera-link').allTextContents()
      return names.length > 0 && names.every((n) => n.includes('onvif-sim'))
    })
    .toBe(true)
  const rows = page.locator('tbody tr').filter({ has: page.getByTestId('camera-link') })
  const count = Math.min(2, await rows.count())
  for (let i = 0; i < count; i++) await rows.nth(i).locator('input[type="checkbox"]').check()
  await page.getByTestId('bulk-password').click()
  // Giữ nguyên mật khẩu của bộ giả lập để các test khác vẫn đăng nhập được camera
  await page.getByTestId('cam-pw').fill('admin123')
  await page.getByTestId('cam-pw2').fill('admin123')
  await page.getByTestId('cam-pw-save').click()
  await expect(page.getByTestId('pw-results').locator('li')).toHaveCount(count, { timeout: 30_000 })
  await expect(page.getByTestId('pw-results')).toContainText('Đã đổi')
  await page.getByRole('button', { name: 'Đóng', exact: true }).last().click()

  await page.getByTestId('camera-link').first().click()
  page.once('dialog', (d) => d.accept())
  await page.getByTestId('reboot').click()
  await expect(page.getByTestId('rebooting')).toBeVisible({ timeout: 15_000 })
  await page.goto('/audit')
  await expect(page.locator('table')).toContainText('REBOOT_CAMERA', { timeout: 15_000 })
  await expect(page.locator('table')).toContainText('CHANGE_CAMERA_PASSWORD')
})

test('US-14 Chụp ảnh PNG tên có tên camera và thời điểm; phóng to số tới 8x', async ({ page }) => {
  await login(page, ADMIN.email, ADMIN.password)
  await page.goto('/live')
  await page.getByTestId('grid-1').click()
  await page.getByTestId('tree-camera').filter({ hasText: 'Bãi xe 1' }).first().click()
  const tile = page.getByTestId('video-tile').first()
  await expect(tile).toHaveAttribute('data-state', 'playing', { timeout: 15_000 })
  const [dl] = await Promise.all([page.waitForEvent('download'), page.getByTestId('snapshot').click()])
  expect(dl.suggestedFilename()).toMatch(/^Bãi xe 1_\d{4}-\d{2}-\d{2}T\d{2}-\d{2}-\d{2}\.png$/)
  for (let i = 0; i < 8; i++) await page.getByTestId('zoom-in').click()
  await expect(tile).toHaveAttribute('data-zoom', '8')
  await expect(page.getByTestId('zoom-level')).toHaveText('8.0x')
})
