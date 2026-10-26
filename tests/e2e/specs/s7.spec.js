// Phase S7: US-36 cảnh báo chuyển động đa kênh (Telegram qua tools/chat-mock), US-42 chuông/toast, US-37 xử lý và
// chuyển giao, US-38 xem lại từ cảnh báo, US-24 cấu hình hình ảnh, US-22 xem lại từ thiết bị (Profile G).
// Khung giờ 22:00–06:00 và chống spam 3 lần/phút kiểm bằng MotionAlertIT (cần điều khiển thời gian).
import { expect, test } from '@playwright/test'
import { ADMIN, DEMO_PASSWORD, login } from './helpers.js'

test.describe.configure({ mode: 'serial' })
const suffix = Date.now().toString(36)
const CHAT = `lan-${suffix}`
let alertUrl = ''

async function cameraPage(page, name) {
  await page.goto('/cameras')
  await page.getByTestId('camera-search').fill(name)
  await expect
    .poll(async () => {
      const names = await page.getByTestId('camera-link').allTextContents()
      return names.length > 0 && names.every((n) => n.includes(name))
    })
    .toBe(true)
  await page.getByTestId('camera-link').first().click()
  await page.waitForURL(/\/cameras\/[0-9a-f-]{36}$/)
  return page.url().split('/').pop()
}

test('US-36 Chuyển động ở Kho Bắc Ninh → Lan nhận Telegram có tên camera, ảnh và liên kết; chuông + toast (US-42)', async ({ page, browser, request }) => {
  test.setTimeout(120_000)
  // Admin: bật kênh Telegram và tạo quy tắc chuyển động cho địa điểm Kho Bắc Ninh, cả ngày, kèm ảnh
  await login(page, ADMIN.email, ADMIN.password)
  await page.goto('/system')
  await expect(page.locator('[data-testid="channels-config"][data-loaded="true"]')).toBeVisible()
  if (!(await page.getByTestId('ch-enabled-TELEGRAM').isChecked())) {
    await page.locator('label.switch', { has: page.getByTestId('ch-enabled-TELEGRAM') }).click()
  }
  await expect(page.getByTestId('ch-enabled-TELEGRAM')).toBeChecked()
  await page.getByTestId('ch-secret-TELEGRAM').fill('123456:test-bot-token')
  await page.getByTestId('ch-save-TELEGRAM').click()
  await expect(page.locator('.toast').last()).toContainText('Đã lưu')

  await page.goto('/alerts')
  await page.getByTestId('tab-rules').click()
  // Xóa quy tắc của lần chạy trước để mỗi chuyển động chỉ sinh một cảnh báo
  page.on('dialog', (d) => d.accept())
  await expect(page.getByTestId('rule-new')).toBeVisible()
  await page.waitForTimeout(1000)
  while ((await page.getByTestId('rule-row').filter({ hasText: 'Chuyển động BN' }).count()) > 0) {
    const n = await page.getByTestId('rule-row').count()
    await page.getByTestId('rule-row').filter({ hasText: 'Chuyển động BN' }).first().getByRole('button', { name: 'Xóa' }).click()
    await expect(page.getByTestId('rule-row')).toHaveCount(n - 1)
  }
  await page.getByTestId('rule-new').click()
  await page.getByTestId('rule-name').fill(`Chuyển động BN ${suffix}`)
  await page.getByTestId('rule-scope-type').selectOption('SITE')
  await page.getByTestId('rule-scope-id').selectOption({ label: 'Kho Bắc Ninh' })
  await page.getByTestId('rule-from-0').fill('00:00')
  await page.getByTestId('rule-to-0').fill('00:00')
  await page.getByTestId('rule-ch-TELEGRAM').check()
  await page.getByTestId('rule-save').click()
  await expect(page.getByTestId('rule-row').filter({ hasText: `Chuyển động BN ${suffix}` })).toBeVisible()

  // Lan (Operator Kho Bắc Ninh) liên kết Telegram rồi mở Xem trực tiếp
  const lan = await browser.newPage()
  await login(lan, 'lan@camhub.local', DEMO_PASSWORD)
  await lan.goto('/alerts')
  await lan.getByTestId('tab-channels').click()
  await lan.getByTestId('my-TELEGRAM').fill(CHAT)
  await lan.getByTestId('save-my-TELEGRAM').click()
  await expect(lan.locator('.toast').last()).toContainText('Đã lưu')
  await lan.getByTestId('bell').click()
  await lan.getByTestId('read-all').click()
  await lan.getByTestId('bell').click()
  await lan.goto('/live')

  // Camera giả lập báo chuyển động (PullPoint → Device Gateway → RabbitMQ → Core); đưa về "không chuyển động"
  // trước để chắc chắn có sự kiện thay đổi trạng thái
  await request.post('http://onvif-sim-1:8080/sim/motion', { data: { state: false } })
  await lan.waitForTimeout(3000)
  const r = await request.post('http://onvif-sim-1:8080/sim/motion', { data: { state: true } })
  expect(r.ok()).toBeTruthy()

  // US-42: chuông tăng số đếm và toast có nội dung cảnh báo
  await expect(lan.getByTestId('bell-count')).toBeVisible({ timeout: 30_000 })
  await expect(lan.locator('.toast').filter({ hasText: 'Chuyển động' }).first()).toBeVisible()

  // Telegram: tin có tên camera, liên kết, kèm ảnh sự kiện (chờ ảnh tối đa 20 giây)
  let msg = null
  await expect
    .poll(async () => {
      const res = await request.get(`http://chat-mock:8026/messages?channel=telegram&chat=${CHAT}`)
      msg = (await res.json())[0] ?? null
      return msg ? msg.has_photo : false
    }, { timeout: 60_000, intervals: [2_000] })
    .toBe(true)
  expect(msg.text).toContain('onvif-sim-1')
  expect(msg.text).toMatch(/\/alerts\?id=[0-9a-f-]{36}/)
  expect(msg.token).toBe('123456:test-bot-token')
  alertUrl = msg.text.match(/\/alerts\?id=[0-9a-f-]{36}/)[0]
  await request.post('http://onvif-sim-1:8080/sim/motion', { data: { state: false } })
  await lan.close()
})

test('US-37/38 Lan nhận xử lý, chuyển giao cho Minh kèm lý do; đóng phải chọn kết quả; xem lại từ thời điểm −10 giây', async ({ page, browser }) => {
  test.skip(!alertUrl, 'Cần cảnh báo từ test US-36')
  await login(page, 'lan@camhub.local', DEMO_PASSWORD)
  await page.goto(alertUrl)
  const detail = page.getByTestId('alert-detail')
  await expect(detail).toBeVisible()
  await expect(page.getByTestId('alert-snapshot')).toBeVisible({ timeout: 15_000 })

  // US-38: liên kết xem lại = thời điểm cảnh báo − 10 giây; có nút xem trực tiếp
  const href = await page.getByTestId('alert-playback').getAttribute('href')
  const at = new Date(decodeURIComponent(href.match(/at=([^&]+)/)[1]))
  await expect(page.getByTestId('alert-live')).toBeVisible()

  await page.getByTestId('alert-ack').click()
  await expect(page.getByTestId('alert-assignee')).toContainText('Trần Thị Lan')
  await page.getByTestId('alert-reassign').click()
  await page.getByTestId('reassign-user').selectOption({ label: 'Lê Văn Minh' })
  await page.getByTestId('reassign-reason').fill('Camera cần kỹ thuật kiểm tra hồng ngoại')
  await page.getByTestId('reassign-submit').click()
  await expect(page.getByTestId('alert-assignee')).toContainText('Lê Văn Minh')
  await expect(page.getByTestId('alert-history')).toContainText('Camera cần kỹ thuật kiểm tra hồng ngoại')

  // Minh nhận thông báo, đóng: thiếu kết quả bị từ chối, chọn kết quả thì đóng được
  const minh = await browser.newPage()
  await login(minh, 'minh@camhub.local', DEMO_PASSWORD)
  await minh.getByTestId('bell').click()
  await expect(minh.getByTestId('notification').first()).toContainText('Bạn được giao xử lý', { timeout: 15_000 })
  await minh.goto(alertUrl)
  await minh.getByTestId('alert-close').click()
  await minh.getByTestId('close-submit').click()
  await expect(minh.locator('.toast').last()).toContainText('Chọn kết quả')
  await minh.getByTestId('close-outcome').selectOption('FALSE_ALARM')
  await minh.getByTestId('close-submit').click()
  await expect(minh.getByTestId('alert-detail')).toContainText('Báo động giả')
  await minh.close()

  // US-38: mở xem lại đúng thời điểm
  await page.getByTestId('alert-playback').click()
  await expect(page).toHaveURL(/\/playback\?camera=/)
  expect(new Date(new URL(page.url()).searchParams.get('at')).getTime()).toBe(at.getTime())
})

test('US-24 Cấu hình hình ảnh: kéo độ sáng rồi khôi phục giá trị trước', async ({ page }) => {
  await login(page, ADMIN.email, ADMIN.password)
  await cameraPage(page, 'onvif-sim-1')
  await page.getByTestId('tab-image').click()
  const value = page.getByTestId('img-b-value')
  await expect(value).toBeVisible({ timeout: 15_000 })
  const before = await value.textContent()
  const slider = page.getByTestId('img-brightness')
  await slider.fill(String(Number(before) > 50 ? 20 : 80))
  await expect(value).not.toHaveText(before)
  await page.waitForTimeout(1500) // đợi áp xuống camera (gộp 400 ms)
  await page.getByTestId('img-restore').click()
  await expect(page.locator('.toast').last()).toContainText('Đã khôi phục')
  await expect(value).toHaveText(before)
  await page.reload()
  await page.getByTestId('tab-image').click()
  await expect(page.getByTestId('img-b-value')).toHaveText(before, { timeout: 15_000 })
})

test('US-22 Xem lại từ thiết bị: camera Profile G có timeline thẻ nhớ và phát được; camera không hỗ trợ bị vô hiệu kèm lý do', async ({ page }) => {
  await login(page, ADMIN.email, ADMIN.password)
  const g = await cameraPage(page, 'onvif-sim-2')
  await page.goto(`/playback?camera=${g}`)
  await page.getByTestId('source-device').click()
  const seg = page.getByTestId('timeline-seg').first()
  await expect(seg).toBeVisible({ timeout: 20_000 })
  await seg.click()
  await expect.poll(() => page.getByTestId('pb-video').evaluate((v) => v.videoWidth), { timeout: 20_000 }).toBeGreaterThan(0)

  const plain = await cameraPage(page, 'onvif-sim-1')
  await page.goto(`/playback?camera=${plain}`)
  await expect(page.getByTestId('source-device')).toBeDisabled()
  await expect(page.getByTestId('source-reason')).toContainText('Profile G')
})
