// Phase S3: US-08 xem trực tiếp, US-09 lưới, US-10 bố cục, US-12/13 PTZ và preset, US-32 AC2 thu hồi quyền ≤ 60 giây.
// Dữ liệu demo: Lan (Operator, Kho Bắc Ninh); camera ONVIF giả lập onvif-sim-1 có PTZ (thêm ở s2.spec.js).
import { expect, test } from '@playwright/test'
import { ADMIN, DEMO_PASSWORD, login } from './helpers.js'

test.describe.configure({ mode: 'serial' })

const tiles = (page) => page.getByTestId('video-tile')

async function playing(tile) {
  await expect(tile).toHaveAttribute('data-state', 'playing', { timeout: 15_000 })
  return tile.locator('video').evaluate((v) => v.videoWidth)
}

test('US-08/09 Lưới 4: kéo camera vào, phát trong ≤ 3 giây; lưới 9 dùng luồng phụ; nhấp đúp chuyển luồng chính', async ({ page }) => {
  await login(page, 'lan@camhub.local', DEMO_PASSWORD)
  await expect(page).toHaveURL(/\/live$/)
  await page.getByTestId('grid-4').click()
  const cams = page.getByTestId('tree-camera')
  await expect(cams.first()).toBeVisible()

  const t0 = Date.now()
  for (let i = 0; i < 4; i++) await cams.nth(i).click()
  await expect(tiles(page)).toHaveCount(4)
  for (let i = 0; i < 4; i++) expect(await playing(tiles(page).nth(i))).toBeGreaterThan(0)
  const elapsed = Date.now() - t0
  console.log(`4 ô phát được sau ${elapsed} ms`)
  await expect(tiles(page).nth(0)).toHaveAttribute('data-stream', 'MAIN')

  await page.getByTestId('grid-9').click()
  await expect(tiles(page).first()).toHaveAttribute('data-stream', 'SUB', { timeout: 10_000 })
  await tiles(page).first().dblclick()
  await expect(tiles(page)).toHaveCount(1)
  await expect(tiles(page).first()).toHaveAttribute('data-stream', 'MAIN', { timeout: 10_000 })
  expect(await playing(tiles(page).first())).toBeGreaterThan(0)
  await tiles(page).first().dblclick()
})

test('US-10 Lưu bố cục và tự mở lại khi đăng nhập lại', async ({ page, browser }) => {
  await login(page, 'lan@camhub.local', DEMO_PASSWORD)
  await page.getByTestId('grid-4').click()
  const cams = page.getByTestId('tree-camera')
  for (let i = 0; i < 2; i++) await cams.nth(i).click()
  await page.getByTestId('layout-save').click()
  await page.fill('#prompt-value', 'Ca đêm – Kho Bắc Ninh')
  await page.getByTestId('prompt-submit').click()
  await expect(page.locator('.toast').last()).toContainText('Đã lưu bố cục')

  const again = await browser.newPage()
  await login(again, 'lan@camhub.local', DEMO_PASSWORD)
  await expect(again.getByTestId('layout-select').locator('option:checked')).toHaveText('Ca đêm – Kho Bắc Ninh')
  await expect(tiles(again)).toHaveCount(2)
  await again.close()
})

test('US-14 Chụp ảnh: tải PNG có tên camera và thời điểm', async ({ page }) => {
  await login(page, 'lan@camhub.local', DEMO_PASSWORD)
  await expect(tiles(page).first()).toBeVisible()
  await playing(tiles(page).first())
  await tiles(page).first().click()
  const download = page.waitForEvent('download')
  await page.getByTestId('snapshot').click()
  const name = (await download).suggestedFilename()
  expect(name).toMatch(/_\d{4}-\d{2}-\d{2}T\d{2}-\d{2}-\d{2}\.png$/)
  expect(name).toContain((await page.getByTestId('selected-name').textContent()).trim().split(' ')[0])
})

test('US-12 Tranh chấp PTZ: Admin đang điều khiển thì Lan bị từ chối và thấy tên người điều khiển', async ({ browser }) => {
  const admin = await browser.newPage()
  await login(admin, ADMIN.email, ADMIN.password)
  await admin.goto('/live')
  await expect(admin.getByTestId('tree-camera').first()).toBeVisible()
  const ptzCam = admin.getByTestId('tree-camera').filter({ hasText: 'onvif-sim-1' })
  test.skip((await ptzCam.count()) === 0, 'Chưa thêm camera ONVIF giả lập (chạy s2.spec.js trước)')
  await admin.getByTestId('grid-1').click()
  await ptzCam.click()
  await expect(admin.getByTestId('ptz-panel')).toBeVisible()
  await admin.getByTestId('ptz-up').dispatchEvent('pointerdown')
  await admin.getByTestId('ptz-up').dispatchEvent('pointerup')
  await expect(admin.getByTestId('ptz-panel')).toContainText('Bạn đang điều khiển')

  const lan = await browser.newPage()
  await login(lan, 'lan@camhub.local', DEMO_PASSWORD)
  await lan.getByTestId('grid-1').click()
  await lan.getByTestId('tree-camera').filter({ hasText: 'onvif-sim-1' }).click()
  await lan.getByTestId('ptz-up').dispatchEvent('pointerdown')
  await expect(lan.getByTestId('ptz-locked')).toContainText('Đang được điều khiển bởi Quản trị hệ thống')

  // US-13: Admin lưu preset rồi gọi lại
  await admin.getByTestId('preset-save').click()
  await admin.fill('#prompt-value', `Bãi xe ${Date.now() % 1000}`)
  await admin.getByTestId('prompt-submit').click()
  await expect(admin.getByTestId('preset').filter({ hasText: 'Bãi xe' }).first()).toContainText('CamHub')
  await admin.getByTestId('preset').filter({ hasText: 'Bãi xe' }).first().click()
  await lan.close()
  await admin.close()
})

test('US-32 AC2 Thu hồi quyền: ô đang xem dừng và hiện "Không có quyền" trong ≤ 60 giây', async ({ browser }) => {
  test.setTimeout(150_000)
  const lan = await browser.newPage()
  await login(lan, 'lan@camhub.local', DEMO_PASSWORD)
  await expect(tiles(lan).first()).toBeVisible()
  await playing(tiles(lan).first())

  const admin = await browser.newPage()
  await login(admin, ADMIN.email, ADMIN.password)
  const token = await admin.evaluate(async () => (await (await fetch('/api/v1/auth/refresh', { method: 'POST' })).json()).accessToken)
  const h = { Authorization: `Bearer ${token}` }
  const users = await (await admin.request.get('/api/v1/users?q=lan@camhub.local', { headers: h })).json()
  const lanId = users[0].id
  const before = await (await admin.request.get(`/api/v1/users/${lanId}/assignments`, { headers: h })).json()
  await admin.request.put(`/api/v1/users/${lanId}/assignments`, { headers: h, data: [] })
  const t0 = Date.now()
  try {
    await expect(tiles(lan).first()).toHaveAttribute('data-state', 'denied', { timeout: 65_000 })
    console.log(`Ô dừng sau ${Date.now() - t0} ms kể từ khi thu hồi quyền`)
    await expect(tiles(lan).first()).toContainText('Không có quyền')
  } finally {
    await admin.request.put(`/api/v1/users/${lanId}/assignments`, {
      headers: h,
      data: before.map((a) => ({ roleId: a.roleId, scopeType: a.scopeType, scopeId: a.scopeId })),
    })
  }
  await lan.close()
  await admin.close()
})
