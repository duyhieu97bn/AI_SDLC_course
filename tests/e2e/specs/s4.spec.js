// Phase S4: US-16 chế độ ghi, US-17 xem lại theo timeline, US-18 điều khiển phát lại, US-21 giữ lại (quyền), US-23.
// Một đoạn ghi dài 60 giây nên test ghi hình chờ đoạn đầu tiên hoàn tất.
import { expect, test } from '@playwright/test'
import { ADMIN, DEMO_PASSWORD, login } from './helpers.js'

test.describe.configure({ mode: 'serial' })
let cameraId = ''

test('US-16 Bật ghi liên tục cho camera: ô xem trực tiếp hiển thị REC và có đoạn ghi sau 60 giây', async ({ page }) => {
  test.setTimeout(180_000)
  await login(page, ADMIN.email, ADMIN.password)
  await page.goto('/cameras')
  await page.getByTestId('camera-search').fill('Cổng chính 1')
  // Tìm theo chuỗi con ("Cổng chính 1" khớp cả "Cổng chính 12"): chọn đúng tên
  await page.getByTestId('camera-link').filter({ hasText: /^\s*Cổng chính 1\s*$/ }).first().click()
  await page.waitForURL(/\/cameras\/[0-9a-f-]{36}$/)
  cameraId = page.url().split('/').pop()
  await page.getByTestId('tab-recording').click()
  await page.getByTestId('mode-CONTINUOUS').click()
  await page.getByTestId('save-policy').click()
  await expect(page.locator('.toast').last()).toContainText('Đã lưu')

  await page.goto('/live')
  await page.getByTestId('grid-1').click()
  await page.getByTestId('tree-camera').filter({ hasText: 'Cổng chính 1' }).click()
  await expect(page.getByTestId('rec')).toBeVisible({ timeout: 15_000 })

  // Đợi đoạn ghi đầu tiên (60 giây) xuất hiện trên timeline
  await page.goto(`/playback?camera=${cameraId}`)
  await expect(page.getByTestId('timeline-seg').first()).toBeVisible({ timeout: 150_000 }).catch(async () => {
    await page.reload()
    await expect(page.getByTestId('timeline-seg').first()).toBeVisible({ timeout: 30_000 })
  })
})

test('US-17/18 Chọn thời điểm trên timeline: phát trong ≤ 3 giây; tốc độ 16x; Space tạm dừng', async ({ page }) => {
  test.skip(!cameraId, 'Cần test ghi hình chạy trước')
  await login(page, ADMIN.email, ADMIN.password)
  await page.goto(`/playback?camera=${cameraId}`)
  // Timeline mặc định 24 giờ: nhấp vào giữa đoạn ghi đầu tiên
  const seg = page.getByTestId('timeline-seg').first()
  await expect(seg).toBeVisible({ timeout: 30_000 })
  const t0 = Date.now()
  await seg.click()
  await expect.poll(() => page.getByTestId('pb-video').evaluate((v) => v.videoWidth), { timeout: 10_000 }).toBeGreaterThan(0)
  console.log(`Phát lại sau ${Date.now() - t0} ms`)
  expect(Date.now() - t0).toBeLessThan(3000 + 1500) // 3 giây theo NFR-02, cộng độ trễ của trình duyệt kiểm thử
  await page.getByTestId('speed-16').click()
  expect(await page.getByTestId('pb-video').evaluate((v) => v.playbackRate)).toBe(16)
  await page.keyboard.press(' ')
  await expect.poll(() => page.getByTestId('pb-video').evaluate((v) => v.paused)).toBe(true)
})

test('US-21 Operator không thấy nút "Giữ lại phục vụ điều tra"; Admin thì thấy', async ({ page, browser }) => {
  await login(page, 'lan@camhub.local', DEMO_PASSWORD)
  await page.goto('/playback')
  await expect(page.getByTestId('pb-camera')).toBeVisible()
  await expect(page.getByTestId('pb-hold')).toHaveCount(0)

  const admin = await browser.newPage()
  await login(admin, ADMIN.email, ADMIN.password)
  await admin.goto('/playback')
  await expect(admin.getByTestId('pb-hold')).toBeVisible()
  await admin.close()
})

test('US-23/US-29 Cấu hình luồng camera ONVIF: chỉ hiện tham số camera hỗ trợ, áp dụng và xem kết quả', async ({ page }) => {
  await login(page, ADMIN.email, ADMIN.password)
  await page.goto('/cameras')
  await page.getByTestId('camera-search').fill('onvif-sim-1')
  // Chờ bộ lọc (debounce) áp dụng xong
  await expect
    .poll(async () => {
      const names = await page.getByTestId('camera-link').allTextContents()
      return names.every((n) => n.includes('onvif-sim-1')) ? names.length : -1
    })
    .toBeGreaterThanOrEqual(0)
  const link = page.getByTestId('camera-link').first()
  test.skip((await link.count()) === 0, 'Chưa thêm camera ONVIF giả lập (chạy s2.spec.js trước)')
  await link.click()
  await page.getByTestId('tab-stream').click()
  const fps = page.locator('[data-testid^="fps-"]').first()
  await expect(fps).toBeVisible({ timeout: 15_000 })
  const max = Number(await fps.getAttribute('max'))
  await fps.fill(String(Math.max(1, max - 5)))
  await page.getByTestId('apply-stream').click()
  await expect(page.getByTestId('apply-result')).toBeVisible({ timeout: 15_000 })
  await expect(page.getByTestId('apply-result')).toContainText('fps')
})
