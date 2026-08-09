// Reproduces the exact trigger for AI_MissingToolResultsError: type a chat
// message while a proposal card is still pending (i.e. its tool call has no
// result yet). Before the fix this killed the session with "An error occurred.";
// after it, the proposal is dismissed and the agent answers normally.
const { chromium } = require('/home/ph/node_modules/playwright')

;(async () => {
  const browser = await chromium.launch({ headless: true })
  const ctx = await browser.newContext({ viewport: { width: 1600, height: 900 }, ignoreHTTPSErrors: true })
  const page = await ctx.newPage()
  const consoleErrors = []
  page.on('console', m => { if (m.type() === 'error') consoleErrors.push(m.text()) })

  await page.goto('https://localhost/atlas/', { waitUntil: 'networkidle', timeout: 60000 }).catch(() => {})
  await page.waitForTimeout(2500)
  const acc = page.getByRole('button', { name: /^accept$/i }).first()
  if (await acc.isVisible().catch(() => false)) { await acc.click(); await page.waitForTimeout(800) }
  await page.getByRole('button', { name: /sign in/i }).first().click()
  await page.waitForTimeout(1000)
  await page.getByLabel(/username|login/i).first().fill('admin')
  await page.getByLabel(/password/i).first().fill('admin')
  await page.keyboard.press('Enter')

  const fab = page.locator('[data-testid="plugin-fab-pythia-plugin"]')
  await fab.waitFor({ timeout: 45000 })
  await page.waitForTimeout(1200)
  await fab.click()
  const panel = page.locator('.cohort-agent-chat').first()
  await panel.waitFor({ timeout: 60000 })
  await page.waitForTimeout(1500)

  const input = panel.getByPlaceholder(/ask pythia|describe the cohort/i).first()
  const errorAlert = panel.locator('.v-alert').first()
  const acceptBtn = () => panel.getByRole('button', { name: /^accept$/i }).first()
  const stopBtn = panel.locator('button.bg-error, button[class*="v-btn"]:has(.mdi-stop)').first()

  await input.click()
  await input.fill('Build a new cohort of patients starting an NSAID who then had a gastrointestinal bleed. Search this data source for the concepts rather than assuming concept ids')
  await input.press('Enter')

  // Wait for a proposal card, answering any question cards along the way
  // (an unanswered ask_user blocks the agent forever).
  const askOptions = panel.locator('.ask-card .option')
  let gotProposal = false
  const deadline = Date.now() + 240000
  let idleMs = 0
  while (Date.now() < deadline) {
    if (await acceptBtn().isVisible().catch(() => false)) { gotProposal = true; break }
    if (await askOptions.first().isVisible().catch(() => false)) {
      const n = await askOptions.count()
      let pick = askOptions.first()
      for (let i = 0; i < n; i++) {
        const l = await askOptions.nth(i).innerText().catch(() => '')
        if (/new|create|build|fresh|scratch/i.test(l)) { pick = askOptions.nth(i); break }
      }
      console.log('answered a question card:', (await pick.innerText().catch(() => '')).split('\n')[0])
      await pick.click()
    }
    if (await errorAlert.isVisible().catch(() => false)) {
      console.log('session errored before any proposal:', (await errorAlert.innerText().catch(() => '')).trim().slice(0, 90))
      break
    }
    // The agent ends its turn and waits; nudge it along (only ever while no
    // proposal is pending, so the bug trigger stays deliberate).
    if (!(await stopBtn.isVisible().catch(() => false))) {
      idleMs += 1000
      if (idleMs >= 12000) {
        idleMs = 0
        await input.click()
        await input.fill('continue')
        await input.press('Enter')
        console.log('nudged')
      }
    } else idleMs = 0
    await page.waitForTimeout(1000)
  }
  console.log('proposal card appeared:', gotProposal)
  if (!gotProposal) { console.log('INCONCLUSIVE: no proposal to type over'); await browser.close(); process.exit(2) }

  const pendingCount = await panel.getByRole('button', { name: /^accept$/i }).count()
  console.log('pending proposal cards:', pendingCount)

  // THE TRIGGER: type instead of clicking Accept/Reject.
  await input.click()
  await input.fill('Actually, hold on — what NSAID ingredients exist in this data source?')
  await input.press('Enter')
  console.log('typed a message while the proposal was pending')

  // The agent should answer, not die.
  const streamed = await stopBtn.waitFor({ state: 'visible', timeout: 30000 }).then(() => true).catch(() => false)
  await page.waitForTimeout(25000)
  const errored = await errorAlert.isVisible().catch(() => false)
  const errorText = errored ? (await errorAlert.innerText().catch(() => '')) : ''
  const dismissed = await panel.getByText(/Dismissed — you replied instead/i).first()
    .isVisible().catch(() => false)
  const transcript = await panel.innerText().catch(() => '')

  console.log('\n--- RESULT ---')
  console.log('streaming started after typed message:', streamed)
  console.log('proposal shows "Dismissed":         ', dismissed)
  console.log('error alert shown:                  ', errored, errorText.trim().slice(0, 80))
  console.log('transcript chars:                   ', transcript.length)
  console.log('console errors:', consoleErrors.length)
  consoleErrors.slice(0, 3).forEach(e => console.log('  ', e.split('\n')[0]))
  await page.screenshot({ path: '/tmp/claude-1000/-home-ph-code-trex-dx/add78e03-c64f-4419-a298-ee410d3d9f70/scratchpad/verify-fix.png' })
  await browser.close()
  process.exit(!errored && streamed ? 0 : 1)
})()
