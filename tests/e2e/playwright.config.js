// Test e2e giao diện CamHub. Chạy qua ./run.sh (container Playwright trong mạng backend của stack dev).
import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './specs',
  timeout: 60_000,
  retries: 0,
  workers: 1, // dùng chung dữ liệu demo, chạy tuần tự cho dễ đọc kết quả
  reporter: [['list'], ['json', { outputFile: 'results/report.json' }]],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'https://nginx',
    ignoreHTTPSErrors: true,
    channel: 'chrome',
    screenshot: 'only-on-failure',
    locale: 'vi-VN',
  },
})
