// Phase S1: US-30 đăng nhập, US-05 tổ chức camera, US-32 phân quyền, US-41 đa ngôn ngữ và responsive.
import { expect, test } from '@playwright/test'
import { ADMIN, DEMO_PASSWORD, expectNoHorizontalScroll, login } from './helpers.js'

test('US-30 Đăng nhập thành công: Admin vào Tổng quan', async ({ page }) => {
  await login(page, ADMIN.email, ADMIN.password)
  await expect(page).toHaveURL(/\/dashboard$/)
  await expect(page.locator('.kpi').first()).toBeVisible()
})

test('US-30 Sai mật khẩu hiện thông báo, không vào được', async ({ page }) => {
  await page.goto('/login')
  await page.fill('#email', 'viewer@camhub.local')
  await page.fill('#password', 'sai-mat-khau')
  await page.click('button[type="submit"]')
  await expect(page.getByTestId('login-error')).toContainText('Email hoặc mật khẩu không đúng')
  await expect(page).toHaveURL(/\/login/)
})

test('US-30 Chưa đăng nhập thì bị chuyển về trang đăng nhập và quay lại trang cũ sau khi đăng nhập', async ({ page }) => {
  await page.goto('/cameras')
  await expect(page).toHaveURL(/\/login\?redirect=(%2F|\/)cameras/)
  await page.fill('#email', ADMIN.email)
  await page.fill('#password', ADMIN.password)
  await page.click('button[type="submit"]')
  await expect(page).toHaveURL(/\/cameras$/)
})

test('US-30 Tải lại trang vẫn giữ phiên (refresh cookie), đăng xuất thì mất phiên', async ({ page }) => {
  await login(page, ADMIN.email, ADMIN.password)
  await page.reload()
  await expect(page).toHaveURL(/\/dashboard$/)
  await page.getByTestId('logout').click()
  await expect(page).toHaveURL(/\/login/)
  await page.goto('/dashboard')
  await expect(page).toHaveURL(/\/login/)
})

test('US-32 Phạm vi theo địa điểm: Lan (Operator, Kho Bắc Ninh) chỉ thấy camera của Kho Bắc Ninh', async ({ page }) => {
  await login(page, 'lan@camhub.local', DEMO_PASSWORD)
  await expect(page).toHaveURL(/\/live$/)
  await page.goto('/cameras')
  await expect(page.getByTestId('camera-row').first()).toBeVisible()
  const sites = await page.locator('.tree details summary .grow').allTextContents()
  expect(sites).toEqual(['Kho Bắc Ninh'])
  const locations = await page.locator('[data-testid="camera-row"] td:nth-child(4)').allTextContents()
  expect(locations.every((l) => l.startsWith('Kho Bắc Ninh'))).toBe(true)
  // Không có quyền quản lý người dùng: menu ẩn, vào thẳng URL thì bị chặn
  await expect(page.locator('.nav-item', { hasText: 'Người dùng' })).toHaveCount(0)
  await page.goto('/users')
  await expect(page).toHaveURL(/\/forbidden$/)
})

test('US-05 Lọc nhanh theo tên và chuyển camera giữ nguyên dữ liệu', async ({ page }) => {
  await login(page, ADMIN.email, ADMIN.password)
  await page.goto('/cameras')
  await page.getByTestId('camera-search').fill('Sảnh')
  // Bộ lọc có debounce: chờ đến khi mọi dòng đều khớp
  await expect
    .poll(async () => {
      const names = await page.locator('[data-testid="camera-row"] td:nth-child(2)').allTextContents()
      return names.length > 0 && names.every((n) => n.includes('Sảnh'))
    })
    .toBe(true)

  // Chuyển camera đầu tiên sang khu vực còn lại của Chi nhánh Hà Nội (chạy lặp lại vẫn đúng)
  // Chỉ camera của Chi nhánh Hà Nội (tên bắt đầu bằng "Sảnh"; kênh đầu ghi "Kênh 02 – Sảnh" cũng khớp chuỗi)
  const row = page.getByTestId('camera-row').filter({ has: page.locator('td:nth-child(4)', { hasText: 'Chi nhánh Hà Nội' }) }).first()
  const name = (await row.locator('td:nth-child(2)').textContent()).trim()
  const current = await row.locator('td:nth-child(4)').textContent()
  const target = current.includes('Quầy giao dịch') ? 'Sảnh' : 'Quầy giao dịch'
  await row.locator('input[type="checkbox"]').check()
  await page.getByTestId('bulk-move').click()
  // Nhiều địa điểm có khu vực "Sảnh": chọn đúng khu vực trong nhóm Chi nhánh Hà Nội
  const value = await page.locator('#target-zone optgroup[label="Chi nhánh Hà Nội"] option', { hasText: target }).getAttribute('value')
  await page.selectOption('#target-zone', value)
  await page.getByTestId('move-submit').click()
  await expect(page.locator('.toast').last()).toContainText('Đã chuyển camera')
  await page.getByTestId('camera-search').fill(name)
  await expect(page.locator('[data-testid="camera-row"] td:nth-child(4)').first()).toContainText(target)
})

test('US-32 Site Manager: chỉ chọn được phạm vi trong địa điểm của mình và không có phạm vi Toàn Tổ chức', async ({ page }) => {
  await login(page, 'huong@camhub.local', DEMO_PASSWORD)
  await page.goto('/users')
  await page.getByTestId('create-user').click()
  await page.getByTestId('add-assignment').click()
  const scopeTypes = await page.locator('[data-testid="assignment-row"] select').nth(1).locator('option').allTextContents()
  expect(scopeTypes).not.toContain('Toàn Tổ chức')
  const sites = await page.locator('[data-testid="assignment-row"] select').nth(2).locator('option:not([disabled])').allTextContents()
  expect(sites).toEqual(['Chi nhánh Hà Nội'])

  // Chọn vai trò Admin thì máy chủ từ chối (INV-22/27)
  await page.fill('#u-email', `nv-${Date.now()}@camhub.local`)
  await page.fill('#u-name', 'Nhân viên mới')
  await page.fill('#u-pass', 'User-Pass-2026')
  await page.locator('[data-testid="assignment-row"] select').nth(0).selectOption({ label: 'Quản trị hệ thống' })
  await page.locator('[data-testid="assignment-row"] select').nth(2).selectOption({ label: 'Chi nhánh Hà Nội' })
  await page.getByTestId('user-save').click()
  await expect(page.getByTestId('user-error')).toContainText('Không gán được vai trò có quyền mà bạn không có')

  // Vai trò Nhân viên giám sát thì được
  await page.locator('[data-testid="assignment-row"] select').nth(0).selectOption({ label: 'Nhân viên giám sát' })
  await page.getByTestId('user-save').click()
  await expect(page.locator('.toast').last()).toContainText('Đã lưu')
})

test('US-41 Đổi ngôn ngữ sang tiếng Anh', async ({ page }) => {
  await login(page, 'minh@camhub.local', DEMO_PASSWORD)
  await page.getByTestId('lang-en').click()
  await expect(page.locator('.topbar h1')).toHaveText('Cameras')
  await page.reload()
  await expect(page.locator('.topbar h1')).toHaveText('Cameras') // ngôn ngữ lưu trong hồ sơ
  await page.getByTestId('lang-vi').click()
  await expect(page.locator('.topbar h1')).toHaveText('Camera')
})

test.describe('US-41 Màn hình điện thoại 360px không cuộn ngang', () => {
  test.use({ viewport: { width: 360, height: 780 } })
  for (const path of ['/login', '/dashboard', '/cameras', '/users', '/live']) {
    test(path, async ({ page }) => {
      if (path !== '/login') await login(page, ADMIN.email, ADMIN.password)
      await page.goto(path)
      await page.waitForLoadState('networkidle')
      await expectNoHorizontalScroll(page)
    })
  }
})
