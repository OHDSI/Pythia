import { expect, type Locator, type Page } from '@playwright/test'

// Drives the real Pythia panel inside ATLAS the way a researcher would: sign
// in, open the panel, ask, then react to whatever the agent puts on screen
// (questions, proposal cards, the step-budget card) until a goal checked
// against WebAPI holds. Proposals are applied by ATLAS itself (auto-approve
// takes the same accept path a click does), so a cohort that shows up in
// WebAPI went through the whole chain: model -> tools -> proposal -> ATLAS's
// CIRCE translation -> WebAPI save.

const ATLAS_USER = process.env.ATLAS_USER || 'admin'
const ATLAS_PASS = process.env.ATLAS_PASS || 'admin'

export interface DriveOptions {
  // Checked whenever the agent is idle; the drive ends when it returns true.
  done: () => Promise<boolean>
  // Sent, one per idle stretch, when the agent stops short of the goal.
  nudges?: string[]
  // Option label to prefer when Pythia asks a question with buttons.
  preferOption?: RegExp
  budgetMs?: number
}

export interface DriveResult { done: boolean; questionsAnswered: number; nudgesSent: number; transcript: string }

export class PythiaPanel {
  readonly panel: Locator
  readonly input: Locator
  private readonly stop: Locator
  private readonly error: Locator

  constructor(readonly page: Page) {
    this.panel = page.locator('.cohort-agent-chat').first()
    this.input = this.panel.getByPlaceholder(/describe the cohort|ask pythia/i).first()
    this.stop = this.panel.locator('button:has(.mdi-stop)').first()
    // The chat's own error alert, not the attachment-errors one.
    this.error = this.panel.locator('.v-alert[type="error"], .v-alert.bg-error, .v-alert.text-error')
      .filter({ hasNot: this.page.locator('[data-testid="pythia-attachment-errors"]') }).first()
  }

  // Auto-approve is a per-browser preference stored in localStorage; turning it
  // on before the app loads means every proposal is accepted through ATLAS's
  // real apply path, without the test having to win races against the cards.
  static async prepare(page: Page): Promise<void> {
    await page.addInitScript(() => {
      try {
        localStorage.setItem('cohort-agent-plugin.autoApprove.v1', 'true')
        localStorage.setItem('pythia.chatPanel.size', JSON.stringify({ w: 900, h: 800 }))
      } catch { /* storage blocked: the driver clicks accept instead */ }
    })
  }

  static async open(page: Page): Promise<PythiaPanel> {
    await PythiaPanel.prepare(page)
    await page.goto('/atlas/', { waitUntil: 'domcontentloaded' })

    const license = page.getByRole('button', { name: /^accept$/i }).first()
    if (await license.isVisible({ timeout: 5_000 }).catch(() => false)) await license.click()

    const signIn = page.getByRole('button', { name: /sign in|log ?in/i }).first()
    if (await signIn.isVisible({ timeout: 5_000 }).catch(() => false)) await signIn.click()
    const user = page.getByLabel(/username|login/i).first()
    await user.waitFor({ state: 'visible', timeout: 30_000 })
    await user.fill(ATLAS_USER)
    const pass = page.getByLabel(/password/i).first()
    await pass.fill(ATLAS_PASS)
    await pass.press('Enter')

    const fab = page.locator('[data-testid="plugin-fab-pythia-plugin"]')
    await fab.waitFor({ state: 'visible', timeout: 60_000 })
    await fab.click()
    const p = new PythiaPanel(page)
    await p.panel.waitFor({ state: 'visible', timeout: 60_000 })
    await p.input.waitFor({ state: 'visible', timeout: 30_000 })
    return p
  }

  async isStreaming(): Promise<boolean> {
    return this.stop.isVisible().catch(() => false)
  }

  async send(text: string): Promise<void> {
    await this.input.fill(text)
    await this.input.press('Enter')
    await this.stop.waitFor({ state: 'visible', timeout: 30_000 }).catch(() => {})
  }

  async transcript(): Promise<string> {
    return (await this.panel.locator('.cohort-agent-chat__messages').innerText().catch(() => '')).trim()
  }

  async drive(opts: DriveOptions): Promise<DriveResult> {
    const budget = opts.budgetMs ?? 12 * 60_000
    const nudges = [...(opts.nudges ?? ['Go ahead and finish building and saving the cohort, then generate it.', 'continue'])]
    const askOptions = this.panel.locator('.ask-card .option')
    const accept = this.panel.locator('button.accept-all, button.accept').first()
    const continueBtn = this.panel.locator('.continue-card .continue-btn').first()
    const unsaved = this.page.getByText(/Unsaved changes will be lost/i).first()

    const t0 = Date.now()
    let idleSince = 0
    let questionsAnswered = 0
    let nudgesSent = 0

    while (Date.now() - t0 < budget) {
      await this.page.waitForTimeout(750)

      if (await this.error.isVisible().catch(() => false)) {
        throw new Error(`Pythia reported an error: ${await this.error.innerText().catch(() => '?')}\n\n${await this.transcript()}`)
      }
      // ATLAS's leave-unsaved-work guard: keep the work, the agent will save it.
      if (await unsaved.isVisible().catch(() => false)) {
        await this.page.getByRole('button', { name: /^cancel$/i }).first().click().catch(() => {})
        continue
      }
      if (await continueBtn.isVisible().catch(() => false)) {
        await continueBtn.click()
        idleSince = 0
        continue
      }
      if (await askOptions.first().isVisible().catch(() => false)) {
        const n = await askOptions.count()
        let pick = askOptions.first()
        if (opts.preferOption) {
          for (let i = 0; i < n; i++) {
            if (opts.preferOption.test(await askOptions.nth(i).innerText().catch(() => ''))) { pick = askOptions.nth(i); break }
          }
        }
        await pick.click()
        questionsAnswered++
        idleSince = 0
        continue
      }
      // Auto-approve should already have resolved these; a card that is still
      // up (storage blocked, a group card) is accepted the way a user would.
      if (await accept.isVisible().catch(() => false)) {
        await accept.click().catch(() => {})
        idleSince = 0
        continue
      }
      if (await this.isStreaming()) { idleSince = 0; continue }

      if (await opts.done()) return { done: true, questionsAnswered, nudgesSent, transcript: await this.transcript() }

      // Idle. Wait out the gap between an accepted proposal and the automatic
      // follow-up request before speaking; a message sent into that gap
      // dismisses whatever the agent is about to do.
      if (!idleSince) { idleSince = Date.now(); continue }
      if (Date.now() - idleSince < 10_000) continue
      const next = nudges.shift()
      if (!next) break
      await this.send(next)
      nudgesSent++
      idleSince = 0
    }
    return { done: await opts.done(), questionsAnswered, nudgesSent, transcript: await this.transcript() }
  }
}

export { expect }
