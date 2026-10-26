import { expect } from '@playwright/test'

export const ADMIN = { email: process.env.E2E_ADMIN_EMAIL ?? 'admin@camhub.local', password: process.env.E2E_ADMIN_PASSWORD }
export const DEMO_PASSWORD = process.env.E2E_DEMO_PASSWORD ?? 'Demo-Pass-2026'

export async function login(page, email, password) {
  await page.goto('/login')
  await page.fill('#email', email)
  await page.fill('#password', password)
  await page.click('button[type="submit"]')
  await expect(page).not.toHaveURL(/\/login/)
}

/** Không có cuộn ngang (US-41 AC2, DoD responsive 360px). */
export async function expectNoHorizontalScroll(page) {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)
  expect(overflow, 'cuộn ngang (px)').toBeLessThanOrEqual(0)
}
