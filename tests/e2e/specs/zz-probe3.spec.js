import { test } from '@playwright/test'
import { DEMO_PASSWORD, login } from './helpers.js'
test('probe grid', async ({ page }) => {
  await login(page, 'lan@camhub.local', DEMO_PASSWORD)
  await page.getByTestId('grid-4').click()
  const cams = page.getByTestId('tree-camera')
  console.log('tree', (await cams.allInnerTexts()).slice(0, 5).join(' | '))
  for (let i = 0; i < 4; i++) await cams.nth(i).click()
  for (const wait of [3000, 12000]) {
    await page.waitForTimeout(wait)
    const tiles = page.getByTestId('video-tile')
    const out = []
    for (let i = 0; i < await tiles.count(); i++) {
      const t = tiles.nth(i)
      out.push(`${(await t.innerText()).split('\n')[0]}:${await t.getAttribute('data-state')}:${await t.getAttribute('data-stream')}:${await t.locator('video').evaluate((v) => v.videoWidth)}`)
    }
    console.log('after', wait, out.join(' | '))
  }
})
