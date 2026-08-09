// Verifies the chat panel's full-screen toggle: expands to (nearly) the whole
// viewport and restores to exactly the previous size.
const { chromium } = require('/home/ph/node_modules/playwright')
;(async () => {
  const b = await chromium.launch({ headless: true })
  const ctx = await b.newContext({ viewport: { width: 1536, height: 864 }, ignoreHTTPSErrors: true })
  const p = await ctx.newPage()
  await p.goto('https://localhost/atlas/', { waitUntil: 'networkidle', timeout: 60000 }).catch(() => {})
  await p.waitForTimeout(2500)
  const acc = p.getByRole('button', { name: /^accept$/i }).first()
  if (await acc.isVisible().catch(() => false)) { await acc.click(); await p.waitForTimeout(800) }
  await p.getByRole('button', { name: /sign in/i }).first().click()
  await p.waitForTimeout(1000)
  await p.getByLabel(/username|login/i).first().fill('admin')
  await p.getByLabel(/password/i).first().fill('admin')
  await p.keyboard.press('Enter')
  await p.locator('[data-testid="plugin-fab-pythia-plugin"]').waitFor({ timeout: 45000 })
  await p.waitForTimeout(1200)
  await p.locator('[data-testid="plugin-fab-pythia-plugin"]').click()
  await p.locator('.cohort-agent-chat').first().waitFor({ timeout: 60000 })
  await p.waitForTimeout(1200)

  const box = () => p.locator('.plugin-overlay-panel').first().boundingBox()
  const before = await box()
  const toggle = p.locator('[data-testid="pythia-fullscreen-toggle"]').first()
  console.log('toggle present:', await toggle.isVisible())
  await toggle.click(); await p.waitForTimeout(700)
  const full = await box()
  await toggle.click(); await p.waitForTimeout(700)
  const after = await box()

  const vw = 1536, vh = 864
  console.log('before:', Math.round(before.width) + 'x' + Math.round(before.height))
  console.log('fullscreen:', Math.round(full.width) + 'x' + Math.round(full.height),
    `(${Math.round(100 * full.width / vw)}% x ${Math.round(100 * full.height / vh)}% of viewport)`)
  console.log('restored:', Math.round(after.width) + 'x' + Math.round(after.height))
  const grew = full.width > before.width * 1.3 && full.height > before.height * 1.1
  const restored = Math.abs(after.width - before.width) < 4 && Math.abs(after.height - before.height) < 4
  console.log('expanded:', grew, ' restored exactly:', restored)
  await b.close()
  process.exit(grew && restored ? 0 : 1)
})()
