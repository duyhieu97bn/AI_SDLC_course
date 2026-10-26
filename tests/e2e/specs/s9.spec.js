// Phase S9: US-07 nhập camera từ tệp CSV (xem trước kèm lỗi từng dòng, bỏ qua dòng lỗi);
// US-15 đàm thoại qua loa camera (onvif-sim-talk: RTSP backchannel giả lập, đếm gói âm thanh nhận được).
import { expect, test } from '@playwright/test'
import { ADMIN, login } from './helpers.js'

const suffix = Date.now().toString(36)

// Micro giả của Chrome (âm "bíp" liên tục) và tự cho phép quyền micro
test.use({
  launchOptions: { args: ['--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream'] },
  permissions: ['microphone'],
})

async function apiToken(page) {
  return page.evaluate(async () => {
    const r = await fetch('/api/v1/auth/refresh', { method: 'POST' })
    return (await r.json()).accessToken
  })
}

test('US-07 Tệp 3 dòng (1 thiếu IP) → xem trước báo lỗi đúng dòng, nhập 2 camera, bỏ qua 1', async ({ page }) => {
  await login(page, ADMIN.email, ADMIN.password)
  const headers = { Authorization: `Bearer ${await apiToken(page)}` }
  const site = `Kho nhập ${suffix}`
  const created = await (await page.request.post('/api/v1/sites', { headers, data: { name: site } })).json()
  await page.request.post(`/api/v1/sites/${created.id}/zones`, { headers, data: { name: 'Cổng' } })

  const csv = '﻿Tên,Địa điểm,Khu vực,Giao thức,Địa chỉ IP,Cổng,Tài khoản,Mật khẩu,URL RTSP\n'
    + `Nhập A ${suffix},${site},Cổng,ONVIF,10.254.254.1,80,admin,pw,\n`
    + `Nhập lỗi ${suffix},${site},Cổng,ONVIF,,80,admin,pw,\n`
    + `Nhập B ${suffix},${site},Cổng,RTSP,,,admin,pw,rtsp://10.254.254.2:554/s1\n`

  await page.goto('/cameras')
  await page.getByTestId('add-camera').click()
  await page.getByTestId('add-import').click()
  await page.getByTestId('import-file').setInputFiles({ name: 'cameras.csv', mimeType: 'text/csv', buffer: Buffer.from(csv) })
  await expect(page.getByTestId('import-row')).toHaveCount(3)
  await expect(page.getByTestId('import-error')).toHaveCount(1)
  await expect(page.getByTestId('import-row').nth(1)).toContainText('Thiếu địa chỉ IP')
  await page.getByLabel('Lưu dù chưa kết nối').check()
  await page.getByTestId('import-run').click()
  const results = page.getByTestId('import-results')
  await expect(results.locator('li')).toHaveCount(3, { timeout: 60_000 })
  await expect(results.locator('.badge-ok')).toHaveCount(2)
  await expect(page.locator('.toast').last()).toContainText('Đã nhập 2 camera, bỏ qua 1 dòng')
})

test('US-15 Giữ "Nhấn giữ để nói" 2 giây → âm thanh G.711 tới loa camera, nhật ký TALK_AUDIO kèm thời lượng', async ({ page, request }) => {
  await login(page, ADMIN.email, ADMIN.password)
  const headers = { Authorization: `Bearer ${await apiToken(page)}` }
  const name = 'Loa cổng (SIM)'
  let cam = (await (await page.request.get(`/api/v1/cameras?q=${encodeURIComponent(name)}`, { headers })).json()).items[0]
  if (!cam) {
    const sites = await (await page.request.get('/api/v1/sites', { headers })).json()
    const res = await page.request.post('/api/v1/cameras', {
      headers,
      data: {
        zoneId: sites[0].zones[0].id,
        name,
        connection: { protocol: 'ONVIF', host: 'onvif-sim-talk', port: 8080, username: 'admin', password: 'admin123' },
        saveUnverified: false,
      },
    })
    expect(res.status(), await res.text()).toBe(201)
    cam = await res.json()
  }
  const before = await (await request.get('http://onvif-sim-talk:8080/sim/talk')).json()

  await page.goto(`/live?camera=${cam.id}`)
  const btn = page.getByTestId('talk-button')
  await expect(btn).toHaveText(/Nhấn giữ để nói/)
  await btn.hover()
  await page.mouse.down()
  await expect(btn).toHaveAttribute('data-state', 'talking', { timeout: 15_000 })
  await page.waitForTimeout(2500)
  await page.mouse.up()
  await expect(page.getByTestId('talk-last')).toContainText(/Vừa nói \d+\.\d giây/)

  const after = await (await request.get('http://onvif-sim-talk:8080/sim/talk')).json()
  expect(after.sessions).toBe(before.sessions + 1)
  // 20 ms mỗi gói: hơn 2 giây âm thanh là trên 80 gói G.711 μ-law (payload type 0)
  expect(after.packets - before.packets).toBeGreaterThan(80)
  expect(after.payloadType).toBe(0)

  const audit = await (await page.request.get(`/api/v1/audit-logs?action=TALK_AUDIO&targetId=${cam.id}&size=1`, { headers })).json()
  expect(JSON.parse(audit.items[0].details).durationSeconds).toBeGreaterThan(1.5)
})
