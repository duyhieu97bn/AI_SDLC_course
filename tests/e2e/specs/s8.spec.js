// Phase S8: US-43 vai trò tùy chỉnh, US-44 đăng nhập LDAP/AD (OpenLDAP dev, deploy/ldap), US-27 cấu hình mẫu,
// US-25 mạng và thời gian (onvif-sim-2 lệch 7 giây), US-19 xem lại đồng bộ.
// US-44 AC2 (vô hiệu hóa khi đồng bộ) và AC3 (thư mục lỗi) kiểm bằng DirectoryIT.
import { expect, test } from '@playwright/test'
import { ADMIN, login } from './helpers.js'

test.describe.configure({ mode: 'serial' })
const suffix = Date.now().toString(36)

test('US-43 Sao chép Operator thành "Bảo vệ ca đêm", bỏ VIEW_PLAYBACK; vai trò hệ thống không sửa/xóa được', async ({ page }) => {
  await login(page, ADMIN.email, ADMIN.password)
  await page.goto('/users')
  await page.getByTestId('role-copy-OPERATOR').click()
  await page.getByTestId('role-name').fill(`Bảo vệ ca đêm ${suffix}`)
  await page.getByTestId('perm-VIEW_PLAYBACK').uncheck()
  await page.getByTestId('role-save').click()
  const row = page.getByTestId('role-row').filter({ hasText: `Bảo vệ ca đêm ${suffix}` })
  await expect(row).toBeVisible()
  await expect(row).not.toContainText('Xem lại')
  await expect(row).toContainText('Xem trực tiếp')
  // Vai trò hệ thống: chỉ có nút Sao chép
  const operator = page.getByTestId('role-row').filter({ hasText: 'Nhân viên giám sát' })
  await expect(operator.getByRole('button', { name: 'Xóa' })).toHaveCount(0)
  await expect(operator.getByRole('button', { name: 'Sửa' })).toHaveCount(0)
  // Vai trò chưa gán thì xóa được
  page.once('dialog', (d) => d.accept())
  await row.getByRole('button', { name: 'Xóa' }).click()
  await expect(row).toHaveCount(0)
})

test('US-44 Hương (nhóm AD CamHub-SiteMgr-HN) đăng nhập lần đầu → Site Manager phạm vi Chi nhánh Hà Nội', async ({ page, browser }) => {
  test.skip(!process.env.E2E_LDAP_BIND_PASSWORD, 'Chưa có deploy/secrets/ldap_bind_password')
  await login(page, ADMIN.email, ADMIN.password)
  await page.goto('/users')
  const tab = page.getByTestId('ldap-tab')
  await expect(page.locator('[data-testid="ldap-tab"][data-loaded="true"]')).toBeVisible()
  await page.getByTestId('ldap-url').fill('ldap://ldap:389')
  await page.getByTestId('ldap-bind').fill('cn=svc-camhub,ou=service,dc=camhub,dc=local')
  await page.getByTestId('ldap-password').fill(process.env.E2E_LDAP_BIND_PASSWORD)
  await page.getByTestId('ldap-user-base').fill('ou=people,dc=camhub,dc=local')
  await page.getByTestId('ldap-group-base').fill('ou=groups,dc=camhub,dc=local')
  if (!(await page.getByTestId('ldap-enabled').isChecked())) {
    await tab.locator('label.switch').click()
  }
  await page.getByTestId('ldap-save').click()
  await expect(page.locator('.toast').last()).toContainText('Đã lưu')
  await page.getByTestId('ldap-test').click()
  await expect(page.getByTestId('ldap-test-result')).toContainText('Kết nối được')

  // Ánh xạ nhóm (thay toàn bộ ánh xạ)
  while ((await page.getByTestId('ldap-mapping').count()) > 0) {
    await page.getByTestId('ldap-mapping').first().getByRole('button').click()
  }
  await page.getByTestId('map-add').click()
  await page.getByTestId('map-group-0').fill('cn=CamHub-SiteMgr-HN,ou=groups,dc=camhub,dc=local')
  await page.getByTestId('map-role-0').selectOption({ label: 'Quản lý địa điểm' })
  await page.getByTestId('map-scope-0').selectOption({ label: 'Chi nhánh Hà Nội' })
  await page.getByTestId('map-save').click()
  await expect(page.locator('.toast').last()).toContainText('Đã lưu')

  const huong = await browser.newPage()
  await login(huong, 'huong.ad', 'AdPass-2026!')
  await expect(huong.getByTestId('profile-link')).toContainText('Nguyễn Thu Hương')
  // Site Manager thấy menu Người dùng (MANAGE_USERS trong phạm vi địa điểm)
  await expect(huong.locator('.nav-item', { hasText: 'Người dùng' })).toBeVisible()
  await huong.close()
})

test('US-27 Áp mẫu "Kho – ghi theo sự kiện" cho camera ONVIF và RTSP: kết quả từng camera', async ({ page }) => {
  await login(page, ADMIN.email, ADMIN.password)
  await page.goto('/templates')
  await page.getByTestId('template-new').click()
  await page.getByTestId('template-name').fill(`Kho – ghi theo sự kiện ${suffix}`)
  await page.getByTestId('tpl-sec-image').check()
  await page.getByTestId('tpl-wdr').check()
  await page.getByTestId('template-save').click()
  await page.getByTestId(`template-apply-Kho – ghi theo sự kiện ${suffix}`).click()
  await page.getByTestId('apply-filter').fill('Kho hàng 1')
  await page.getByTestId('apply-cam-Kho hàng 1').check()
  await page.getByTestId('apply-filter').fill('onvif-sim-1')
  await page.locator('[data-testid^="apply-cam-"]').first().check()
  await page.getByTestId('apply-submit').click()
  const rows = page.getByTestId('apply-result-row')
  await expect(rows).toHaveCount(2, { timeout: 30_000 })
  // Camera RTSP không cấu hình hình ảnh qua ONVIF → một phần (bỏ qua)
  await expect(rows.filter({ hasText: 'Kho hàng 1' })).toContainText('Một phần')
  await expect(rows.filter({ hasText: 'onvif-sim-1' })).toContainText(/Thành công|Một phần/)
})

test('US-25 Camera lệch 7 giây được đánh dấu; đổi IP cảnh báo mất kết nối tạm thời; NTP sửa lệch giờ', async ({ page, request }) => {
  await login(page, ADMIN.email, ADMIN.password)
  await request.post('http://onvif-sim-2:8080/sim/clock', { data: { offsetSeconds: 7 } })
  await page.goto('/cameras')
  await page.getByTestId('camera-search').fill('onvif-sim-2')
  await expect.poll(async () => (await page.getByTestId('camera-link').allTextContents()).every((n) => n.includes('onvif-sim-2'))).toBe(true)
  await page.getByTestId('camera-link').first().click()
  await page.getByTestId('tab-network').click()
  await expect(page.getByTestId('clock-drift')).toContainText(/Lệch [67]\.\d giây/, { timeout: 15_000 })
  await expect(page.getByTestId('clock-drift')).toHaveClass(/badge-warn/)

  // Danh sách camera đánh dấu "Lệch giờ"
  await page.goto('/cameras')
  await page.getByTestId('camera-search').fill('onvif-sim-2')
  await expect(page.getByTestId('clock-skew').first()).toBeVisible({ timeout: 15_000 })

  // Đổi IP: hiện cảnh báo trước khi áp (hủy để không đổi địa chỉ camera giả lập)
  await page.getByTestId('camera-link').first().click()
  await page.getByTestId('tab-network').click()
  await page.getByTestId('net-apply').click()
  await expect(page.getByTestId('net-warning')).toContainText('mất kết nối tạm thời')
  await page.getByRole('button', { name: 'Hủy' }).click()

  // NTP: bộ giả lập đồng bộ lại đồng hồ (SIM_NTP_SYNC)
  await page.getByTestId('net-ntp').fill('pool.ntp.org')
  await page.getByTestId('net-ntp-apply').click()
  await expect(page.getByTestId('clock-drift')).toHaveClass(/badge-ok/, { timeout: 15_000 })
  await request.post('http://onvif-sim-2:8080/sim/clock', { data: { offsetSeconds: 7 } })
})

test('US-19 Xem lại đồng bộ: tối đa 4 camera; phát cùng mốc, lệch ≤ 1 giây', async ({ page }) => {
  await login(page, ADMIN.email, ADMIN.password)
  await page.goto('/playback')
  await page.getByTestId('sync-mode').check()
  for (const n of ['Cổng chính 1', 'Cổng chính 2', 'Cổng chính 3', 'Bãi xe 1']) await page.getByTestId(`sync-cam-${n}`).check()
  await page.getByTestId('sync-cam-Bãi xe 2').check()
  await expect(page.locator('.toast').last()).toContainText('tối đa 4')
  await expect(page.getByTestId('sync-cam-Bãi xe 2')).not.toBeChecked()
  const seg = page.getByTestId('timeline-seg').first()
  await expect(seg).toBeVisible({ timeout: 20_000 })
  await seg.click()
  await expect.poll(() => page.getByTestId('sync-video-Cổng chính 1').evaluate((v) => v.videoWidth), { timeout: 15_000 }).toBeGreaterThan(0)
  await page.waitForTimeout(3000)
  expect(Number(await page.getByTestId('sync-drift').getAttribute('data-drift'))).toBeLessThanOrEqual(1)
})
