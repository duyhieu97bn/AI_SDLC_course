// Phase S9: US-07 nhập camera từ tệp CSV (xem trước kèm lỗi từng dòng, bỏ qua dòng lỗi).
import { expect, test } from '@playwright/test'
import { ADMIN, login } from './helpers.js'

const suffix = Date.now().toString(36)

test('US-07 Tệp 3 dòng (1 thiếu IP) → xem trước báo lỗi đúng dòng, nhập 2 camera, bỏ qua 1', async ({ page }) => {
  await login(page, ADMIN.email, ADMIN.password)
  const token = await page.evaluate(async () => {
    const r = await fetch('/api/v1/auth/refresh', { method: 'POST' })
    return (await r.json()).accessToken
  })
  const headers = { Authorization: `Bearer ${token}` }
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
