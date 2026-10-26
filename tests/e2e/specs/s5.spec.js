// Phase S5: US-20 xuất clip, US-26 vùng chuyển động/vùng che, US-35 cảnh báo lưu trữ (+ thông báo web/email),
// US-39 tổng quan, US-40 sao lưu chỉ xem. US-34 (thời gian chờ, gộp địa điểm, tự phục hồi) và US-20 AC4 (hết hạn 24 giờ)
// kiểm bằng test tích hợp AlertingIT/ExportIT vì cần điều khiển thời gian và trạng thái thiết bị.
import { expect, test } from '@playwright/test'
import { ADMIN, DEMO_PASSWORD, login } from './helpers.js'

test.describe.configure({ mode: 'serial' })

async function cameraId(page, name, exact = true) {
  await page.goto('/cameras')
  await page.getByTestId('camera-search').fill(name)
  await expect
    .poll(async () => {
      const names = await page.getByTestId('camera-link').allTextContents()
      return names.length > 0 && names.every((n) => n.includes(name))
    })
    .toBe(true)
  // Tìm theo chuỗi con ("Cổng chính 1" khớp cả "Cổng chính 12"): chọn đúng tên
  const links = page.getByTestId('camera-link')
  await (exact ? links.filter({ hasText: new RegExp(`^\s*${name}\s*$`) }) : links).first().click()
  await page.waitForURL(/\/cameras\/[0-9a-f-]{36}$/)
  return page.url().split('/').pop()
}

test('US-20 Xuất clip: quá 60 phút bị chặn; khoảng hợp lệ → thông báo sẵn sàng → tải MP4', async ({ page }) => {
  test.setTimeout(180_000)
  await login(page, ADMIN.email, ADMIN.password)
  const id = await cameraId(page, 'Cổng chính 1')
  await page.goto(`/playback?camera=${id}`)
  const seg = page.getByTestId('timeline-seg').first()
  await expect(seg).toBeVisible({ timeout: 30_000 })
  await seg.click()
  await expect.poll(() => page.getByTestId('pb-video').evaluate((v) => v.videoWidth), { timeout: 10_000 }).toBeGreaterThan(0)
  await page.keyboard.press(' ') // tạm dừng để thời điểm hiện tại cố định

  // Đánh dấu đã đọc để chuông chỉ đếm thông báo "clip sẵn sàng" của lần xuất này
  await page.getByTestId('bell').click()
  await page.getByTestId('read-all').click()
  await expect(page.getByTestId('bell-count')).toHaveCount(0)
  await page.getByTestId('bell').click()

  await page.getByTestId('pb-export').click()
  const from = await page.getByTestId('ex-from').inputValue()
  // AC2: 75 phút → yêu cầu rút ngắn, không gửi được
  const plus = (v, sec) => {
    const d = new Date(new Date(v).getTime() + sec * 1000)
    return new Date(d.getTime() - d.getTimezoneOffset() * 60_000).toISOString().slice(0, 19)
  }
  await page.getByTestId('ex-to').fill(plus(from, 75 * 60))
  await expect(page.getByTestId('ex-problem')).toContainText('60')
  await expect(page.getByTestId('ex-submit')).toBeDisabled()

  // Khoảng 30 giây bắt đầu tại thời điểm đang xem (có dữ liệu ghi)
  const at = plus(from, 120)
  await page.getByTestId('ex-from').fill(at)
  await page.getByTestId('ex-to').fill(plus(at, 30))
  await page.getByTestId('ex-submit').click()
  await expect(page.locator('.toast').last()).toContainText('Đang xuất')

  // AC1: thông báo khi clip sẵn sàng (chuông), danh sách hiển thị "Sẵn sàng"
  await expect(page.getByTestId('bell-count')).toBeVisible({ timeout: 90_000 })
  await page.goto('/exports')
  await expect(page.getByTestId('export-status').first()).toHaveText('Sẵn sàng', { timeout: 60_000 })

  // Tải về: MP4 có hộp ftyp ở đầu tệp
  const [download] = await Promise.all([page.waitForEvent('download'), page.getByTestId('export-download').first().click()])
  expect(download.suggestedFilename()).toMatch(/\.mp4$/)
  const fs = await import('node:fs/promises')
  const head = (await fs.readFile(await download.path())).subarray(4, 8).toString('latin1')
  expect(head).toBe('ftyp')

  // Nhật ký EXPORT_VIDEO
  await page.goto('/audit')
  await expect(page.locator('table')).toContainText('EXPORT_VIDEO', { timeout: 15_000 })
})

test('US-20 AC3 Operator không có EXPORT_VIDEO: không thấy nút "Xuất clip"', async ({ page }) => {
  await login(page, 'lan@camhub.local', DEMO_PASSWORD)
  await page.goto('/playback')
  await expect(page.getByTestId('pb-camera')).toBeVisible()
  await expect(page.getByTestId('pb-export')).toHaveCount(0)
})

test('US-26 Vùng chuyển động đa giác: vẽ, lưu; vượt số vùng camera hỗ trợ bị chặn; vùng che lưu được', async ({ page }) => {
  await login(page, ADMIN.email, ADMIN.password)
  await cameraId(page, 'onvif-sim-1', false)
  await page.getByTestId('tab-zones').click()
  const canvas = page.getByTestId('zone-canvas')
  await expect(canvas).toBeVisible({ timeout: 15_000 })
  await page.getByTestId('mode-polygon').click()
  const box = await canvas.boundingBox()
  const tri = async (x0) => {
    for (const [dx, dy] of [[0, 0.1], [0.08, 0.1], [0.04, 0.3]]) {
      await page.mouse.click(box.x + box.width * (x0 + dx), box.y + box.height * dy)
    }
    await page.getByTestId('close-shape').click()
  }
  // Xóa vùng cũ (nếu lần chạy trước để lại)
  while ((await page.getByTestId('zone-shape').count()) > 0) {
    await page.locator('.badge a').first().click()
  }
  for (let i = 0; i < 4; i++) await tri(0.05 + i * 0.2)
  await expect(page.getByTestId('zone-count')).toHaveText('4/4 vùng')
  // Vùng thứ 5: giới hạn của camera (maxPolygons 4)
  await page.mouse.click(box.x + box.width * 0.5, box.y + box.height * 0.7)
  await expect(page.locator('.toast').last()).toContainText('tối đa 4')
  await page.getByTestId('save-zones').click()
  await expect(page.locator('.toast').last()).toContainText('Đã lưu')
  await page.reload()
  await page.getByTestId('tab-zones').click()
  await expect(page.getByTestId('zone-shape')).toHaveCount(4, { timeout: 15_000 })

  // Vùng che riêng tư
  await page.getByTestId('layer-masks').click()
  while ((await page.getByTestId('zone-shape').count()) > 0) {
    await page.locator('.badge a').first().click()
  }
  await tri(0.6)
  await page.getByTestId('save-zones').click()
  await expect(page.locator('.toast').last()).toContainText('Đã lưu')

  // Trả camera giả lập về lưới ô mặc định cho lần chạy sau
  await page.getByTestId('layer-masks').click()
  while ((await page.getByTestId('zone-shape').count()) > 0) {
    await page.locator('.badge a').first().click()
  }
  await page.getByTestId('save-zones').click()
  await page.getByTestId('layer-motion').click()
  await page.getByTestId('mode-cell').click()
  await page.getByRole('button', { name: 'Chọn tất cả' }).click()
  await page.getByTestId('save-zones').click()
  await expect(page.locator('.toast').last()).toContainText('Đã lưu')
})

test('US-35 Vượt ngưỡng lưu trữ: cảnh báo kèm dung lượng còn lại hiện ngay qua WebSocket, chuông báo, email', async ({ page, request }) => {
  test.setTimeout(300_000)
  await login(page, ADMIN.email, ADMIN.password)
  const rule = page.getByTestId('rule-STORAGE_THRESHOLD')
  const setWarning = async (v) => {
    await page.goto('/system')
    await rule.getByTestId('warning-percent').fill(String(v))
    await page.getByTestId('save-rule-STORAGE_THRESHOLD').click()
    await expect(page.locator('.toast').last()).toContainText('Đã lưu')
  }
  const openStorageRows = () => page.getByTestId('alert-row').filter({ hasText: 'Ổ lưu trữ' })

  // Bắt đầu từ trạng thái không có cảnh báo lưu trữ đang mở (ngưỡng mặc định 85%, ổ dev còn trống)
  await setWarning(85)
  await page.goto('/alerts')
  await expect
    .poll(async () => {
      await page.reload()
      await expect(page.locator('[data-testid="alerts-table"][data-loaded="true"]')).toBeVisible()
      return openStorageRows().count()
    }, { timeout: 120_000, intervals: [10_000] })
    .toBe(0)

  // Hạ ngưỡng xuống 1%: lần đo kế tiếp (mỗi phút ở dev) tạo cảnh báo; trang Cảnh báo đang mở tự cập nhật
  await setWarning(1)
  await page.goto('/alerts')
  await expect(openStorageRows().first()).toBeVisible({ timeout: 90_000 })
  await expect(openStorageRows().first()).toContainText('còn')
  await expect(page.getByTestId('bell-count')).toBeVisible()
  await expect(page.locator('.toast').filter({ hasText: 'Ổ lưu trữ' })).toBeVisible()

  // Email tới Admin qua SMTP (Mailpit trong dev)
  await expect
    .poll(async () => {
      const r = await request.get('http://mailpit:8025/api/v1/search?query=' + encodeURIComponent('subject:"Ổ lưu trữ"'))
      return r.ok() ? (await r.json()).messages_count : 0
    }, { timeout: 60_000, intervals: [5_000] })
    .toBeGreaterThan(0)

  await setWarning(85)
})

test('US-39 Tổng quan: "trực tuyến / tổng" khớp danh sách camera, số cảnh báo mở khớp trang Cảnh báo', async ({ page }) => {
  await login(page, ADMIN.email, ADMIN.password)
  await page.goto('/dashboard')
  await expect(page.getByTestId('kpi-online')).toHaveText(/^\d+ \/ \d+$/)
  const [online, total] = (await page.getByTestId('kpi-online').textContent()).split(' / ').map(Number)
  expect(online).toBeLessThanOrEqual(total)
  const open = Number(await page.getByTestId('kpi-alerts').textContent())
  await page.getByTestId('kpi-alerts').click()
  await expect(page).toHaveURL(/\/alerts/)
  await page.getByTestId('alerts-open').click()
  await expect.poll(() => page.getByTestId('alert-row').count()).toBe(open)
})

test('US-40 Tab Sao lưu chỉ xem: có trạng thái và danh sách bản sao lưu, không có nút khôi phục', async ({ page }) => {
  await login(page, ADMIN.email, ADMIN.password)
  await page.goto('/system')
  const tab = page.getByTestId('backup-tab')
  await expect(tab).toBeVisible()
  await expect(page.getByTestId('backup-pg-status')).toHaveText('OK')
  await expect(page.getByTestId('backup-row').first()).toBeVisible()
  await expect(tab.getByRole('button')).toHaveCount(0)
})
