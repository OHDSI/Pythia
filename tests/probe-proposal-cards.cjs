// Fast check: does a proposal card actually RENDER, and does it sit after the
// tool chip that produced it (rather than above the model's summary text)?
const { chromium } = require('/home/ph/node_modules/playwright')
;(async () => {
  const b = await chromium.launch({ headless: true })
  const ctx = await b.newContext({ viewport: { width: 1536, height: 864 }, ignoreHTTPSErrors: true })
  const p = await ctx.newPage()
  const errs = []
  p.on('console', m => { if (m.type() === 'error') errs.push(m.text().slice(0, 120)) })
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
  const panel = p.locator('.cohort-agent-chat').first()
  await panel.waitFor({ timeout: 60000 })
  await p.waitForTimeout(1500)
  const input = panel.getByPlaceholder(/ask pythia|describe the cohort/i).first()
  await input.click()
  await input.fill('Set the cohort entry event to a sinusitis diagnosis')
  await input.press('Enter')

  const card = panel.locator('.proposal-card').first()
  const rendered = await card.waitFor({ state: 'visible', timeout: 180000 }).then(() => true).catch(() => false)
  console.log('proposal card rendered:', rendered)
  if (rendered) {
    const order = await panel.evaluate(el => {
      const nodes = [...el.querySelectorAll('.cohort-agent-chat__tool-chip, .proposal-card, .cohort-agent-chat__markdown')]
      return nodes.map(n => n.classList.contains('proposal-card') ? 'CARD'
        : n.classList.contains('cohort-agent-chat__tool-chip') ? 'toolchip' : 'text').join(' > ')
    })
    console.log('DOM order:', order)
    console.log('accept button:', await panel.locator('button.accept').count())
    // width check: a card squeezed inside the flex row shrinks to its content
    const w = await panel.evaluate(el => {
      const card = el.querySelector('.proposal-card')
      const box = el.querySelector('.cohort-agent-chat__messages') || el
      return card ? Math.round(100 * card.getBoundingClientRect().width / box.getBoundingClientRect().width) : 0
    })
    console.log('card width vs panel:', w + '%')
    // dots must never precede a card
    const dotsAboveCard = await panel.evaluate(el => {
      const nodes = [...el.querySelectorAll('.cohort-agent-chat__typing, .proposal-card')]
      const firstCard = nodes.findIndex(n => n.classList.contains('proposal-card'))
      return nodes.slice(0, firstCard === -1 ? 0 : firstCard).some(n => n.classList.contains('cohort-agent-chat__typing'))
    })
    console.log('typing dots above a card:', dotsAboveCard)
    // Inline means the element right before the card is its own tool chip, and
    // that the card is NOT the last thing in the transcript.
    const placement = await panel.evaluate(el => {
      const card = el.querySelector('.proposal-card')
      if (!card) return 'none'
      const prev = card.previousElementSibling
      const all = [...el.querySelectorAll('.cohort-agent-chat__row, .proposal-card')]
      return JSON.stringify({
        prev: prev ? (prev.className || prev.tagName).toString().slice(0, 40) : null,
        isLast: all[all.length - 1] === card,
      })
    })
    console.log('placement:', placement)
  }
  console.log('console errors:', errs.length, errs.slice(0, 2).join(' | '))
  await b.close()
  process.exit(rendered ? 0 : 1)
})()
