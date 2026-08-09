// Records a walkthrough video of Pythia inside the atlas3-trex stack.
//   node record-pythia.cjs [outDir]
// 16:9 @ 1600x900, on-screen captions + a synthetic cursor.
//
// The core is an event-reactive driver: it polls the panel ~2.5x/second and
// responds to whatever Pythia puts on screen (a question card, a proposal,
// ATLAS's unsaved-changes guard) instead of sleeping on fixed timers. Questions
// are answered by CLICKING the options Pythia offers — typing a free-text reply
// at a card with buttons looks nonsensical on camera.
const { chromium } = require('/home/ph/node_modules/playwright')
const fs = require('fs')
const { execSync } = require('child_process')
const path = require('path')

const BASE = 'https://localhost/atlas/'
const USER = process.env.ATLAS_USER || 'admin'
const PASS = process.env.ATLAS_PASS || 'admin'
const OUT = process.argv[2] || '/tmp/claude-1000/-home-ph-code-trex-dx/add78e03-c64f-4419-a298-ee410d3d9f70/scratchpad/video'
const W = 1920          // video size
const H = 1080
const VW = 1536         // viewport; upscaled to W x H => ~125% zoom, bigger text
const VH = 864

const OVERLAY = `
(() => {
  const install = () => {
    if (!document.body || document.getElementById('__rec_cursor')) return
    const cur = document.createElement('div')
    cur.id = '__rec_cursor'
    cur.style.cssText = 'position:fixed;z-index:2147483647;width:22px;height:22px;margin:-11px 0 0 -11px;' +
      'border-radius:50%;background:rgba(239,68,68,.35);border:2px solid rgba(239,68,68,.95);' +
      'pointer-events:none;transition:transform .08s linear;left:-100px;top:-100px'
    document.body.appendChild(cur)
    const cap = document.createElement('div')
    cap.id = '__rec_caption'
    cap.style.cssText = 'position:fixed;z-index:2147483647;bottom:28px;left:28px;' +
      'padding:10px 22px;border-radius:999px;background:rgba(17,24,39,.88);color:#fff;' +
      'font:600 16px/1.3 system-ui,-apple-system,Segoe UI,Roboto,sans-serif;letter-spacing:.01em;' +
      'pointer-events:none;opacity:0;transition:opacity .35s ease;box-shadow:0 6px 24px rgba(0,0,0,.3)'
    document.body.appendChild(cap)
    addEventListener('mousemove', e => { cur.style.left = e.clientX + 'px'; cur.style.top = e.clientY + 'px' }, true)
    addEventListener('mousedown', () => { cur.style.transform = 'scale(1.9)'; cur.style.background = 'rgba(239,68,68,.6)' }, true)
    addEventListener('mouseup', () => { cur.style.transform = 'scale(1)'; cur.style.background = 'rgba(239,68,68,.35)' }, true)
    window.__caption = t => { cap.textContent = t || ''; cap.style.opacity = t ? '1' : '0' }
  }
  if (document.body) install()
  else document.addEventListener('DOMContentLoaded', install)
  const iv = setInterval(() => { install(); if (document.getElementById('__rec_cursor')) clearInterval(iv) }, 100)
})()
`

const steps = []
const ok = (n, e) => { steps.push({ name: n, ok: true, extra: e }); console.log(`  PASS  ${n}${e ? ' — ' + e : ''}`) }
const fail = (n, e) => { steps.push({ name: n, ok: false, extra: String(e) }); console.log(`  FAIL  ${n} — ${e}`) }

// The editor renders target/event cohorts for an UNSAVED analysis too, so
// asserting on the page can pass while nothing was persisted. Ask the database.
const psql = sql => {
  try {
    return execSync(
      `docker exec atlas3-trex-atlas3-postgres-1 psql -U postgres -d postgres -t -A -c ${JSON.stringify(sql)}`,
      { encoding: 'utf8' }
    ).trim()
  } catch { return '' }
}

async function main() {
  fs.rmSync(OUT, { recursive: true, force: true })

  const baseCohort = Number(psql('select coalesce(max(id),0) from webapi.cohort_definition')) || 0
  const basePathway = Number(psql('select coalesce(max(id),0) from webapi.pathway_analysis')) || 0
  const baseRuns = Number(psql(
    "select count(*) from webapi.batch_job_instance where job_name = 'generatePathwayAnalysis'")) || 0
  console.log(`  baseline: cohorts<=${baseCohort}, pathways<=${basePathway}`)
  fs.mkdirSync(OUT, { recursive: true })

  const browser = await chromium.launch({ headless: true, slowMo: 220 })
  const ctx = await browser.newContext({
    viewport: { width: VW, height: VH },
    recordVideo: { dir: OUT, size: { width: VW, height: VH } },
    ignoreHTTPSErrors: true,
    deviceScaleFactor: 1.25,   // ~125% zoom so chat text survives re-compression
  })
  await ctx.addInitScript(OVERLAY)
  await ctx.addInitScript(() => {
    try { localStorage.setItem('pythia.chatPanel.size', JSON.stringify({ w: 880, h: 760 })) } catch {}
  })
  const page = await ctx.newPage()

  const consoleErrors = []
  const netFailures = []
  page.on('console', m => { if (m.type() === 'error') consoleErrors.push(m.text()) })
  page.on('requestfailed', r => netFailures.push(`${r.method()} ${r.url()} — ${r.failure()?.errorText}`))
  page.on('response', r => { if (r.status() >= 400) netFailures.push(`${r.status()} ${r.url()}`) })

  const caption = async t => { try { await page.evaluate(x => window.__caption?.(x), t) } catch {} }

  // Full-bleed card so a reviewer arriving with zero context knows what this is.
  const card = async (title, sub) => {
    await page.evaluate(([t, s2]) => {
      let el = document.getElementById('__rec_card')
      if (!el) {
        el = document.createElement('div')
        el.id = '__rec_card'
        el.style.cssText = 'position:fixed;inset:0;z-index:2147483646;display:flex;' +
          'flex-direction:column;align-items:center;justify-content:center;gap:12px;' +
          'background:#ffffff;color:#1f425a;font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;' +
          'transition:opacity .4s ease'
        document.body.appendChild(el)
      }
      el.style.opacity = '1'
      const cur = document.getElementById('__rec_cursor')
      if (cur) cur.style.display = 'none'
      el.innerHTML =
        '<div style="font-size:52px;font-weight:700;line-height:1.2;letter-spacing:-.01em;color:#1f425a">' + t + '</div>' +
        '<div style="width:64px;height:3px;background:#f0872a;border-radius:2px"></div>' +
        '<div style="font-size:24px;font-weight:400;line-height:1.4;color:#5b7185">' + s2 + '</div>'
    }, [title, sub])
  }
  const hideCard = async () => {
    await page.evaluate(() => {
      const el = document.getElementById('__rec_card')
      if (el) { el.style.opacity = '0'; setTimeout(() => el.remove(), 500) }
      const cur = document.getElementById('__rec_cursor')
      if (cur) cur.style.display = ''
    })
  }
  const beat = ms => page.waitForTimeout(ms)

  try {
    await page.goto(BASE, { waitUntil: 'domcontentloaded', timeout: 60000 })
    await card('Pythia', 'AI study-design advisor for ATLAS v3.0')
    await beat(2400)
    await hideCard()
    await caption('Opening ATLAS 3')
    await page.waitForLoadState('networkidle', { timeout: 60000 }).catch(() => {})
    await beat(1100)
    ok('ATLAS loaded', await page.title())

    const licenseAccept = page.getByRole('button', { name: /^accept$/i }).first()
    if (await licenseAccept.isVisible().catch(() => false)) {
      await caption('Accepting the SNOMED license agreement')
      await beat(700)
      await licenseAccept.click()
      await beat(600)
      ok('License agreement accepted')
    }

    await caption('Signing in')
    const signIn = page.getByRole('button', { name: /sign in|log ?in/i }).first()
    if (await signIn.isVisible().catch(() => false)) { await beat(400); await signIn.click(); await beat(600) }
    const userField = page.getByLabel(/username|login/i).first()
    await userField.waitFor({ state: 'visible', timeout: 20000 })
    await userField.click()
    await userField.pressSequentially(USER, { delay: 40 })
    const passField = page.getByLabel(/password/i).first()
    await passField.click()
    await passField.pressSequentially(PASS, { delay: 40 })
    await beat(400)
    await passField.press('Enter')
    ok('Credentials submitted')

    await caption('Signed in. Pythia is available bottom-right')
    const fab = page.locator('[data-testid="plugin-fab-pythia-plugin"]')
    await fab.waitFor({ state: 'visible', timeout: 45000 })
    await beat(1200)
    ok('Pythia FAB rendered')

    await caption('Opening Pythia, the ATLAS design assistant')
    await fab.hover(); await beat(500)
    await fab.click()
    const panel = page.locator('.cohort-agent-chat').first()
    await panel.waitFor({ state: 'visible', timeout: 60000 })
    await beat(1400)
    ok('Pythia panel mounted')

    // --- locators the driver reacts to -------------------------------------
    const input = panel.getByPlaceholder(/ask pythia|describe the cohort/i).first()
    const stopBtn = panel.locator('button.bg-error, button[class*="v-btn"]:has(.mdi-stop)').first()
    const planHeader = panel.locator('.cohort-agent-plan__header').first()
    const askOptions = panel.locator('.ask-card .option')
    const acceptBtn = () => panel.locator('button.accept, button.accept-all').first()
    const autoApprove = panel.locator('button[title^="Auto-approve"]').first()
    const unsavedDialog = page.getByText(/Unsaved changes will be lost/i).first()
    const errorAlert = panel.locator('.v-alert').first()

    const collapsePlan = async () => {
      if (!(await planHeader.isVisible().catch(() => false))) return false
      const expanded = await planHeader.locator('.mdi-menu-down').count().then(c => c > 0).catch(() => false)
      if (!expanded) return false
      await planHeader.click()
      return true
    }

    const say = async text => {
      await input.click()
      await input.pressSequentially(text, { delay: 22 })
      await beat(350)
      // A proposal may have landed while we were typing. Sending now would
      // dismiss it (that is what send() does by design) — so drop the message
      // and let the loop click the card instead, like a human would.
      if (await acceptBtn().isVisible().catch(() => false)) {
        await input.fill('')
        return false
      }
      await input.press('Enter')
      // Wait for the composer to flip to the stop button, else the caller sees a
      // send button that simply hasn't swapped yet and fires again immediately.
      await stopBtn.waitFor({ state: 'visible', timeout: 20000 }).catch(() => {})
      return true
    }

    await caption('Asking Pythia for a treatment pathway analysis')
    await input.waitFor({ state: 'visible', timeout: 20000 })
    await input.click()
    const PROMPT = 'Create a treatment pathway analysis for patients with sinusitis, showing which antibiotics they receive over time'
    await input.pressSequentially(PROMPT, { delay: 26 })
    await beat(600)
    ok('Prompt typed', PROMPT)
    await caption('Pythia is working')
    await input.press('Enter')
    await stopBtn.waitFor({ state: 'visible', timeout: 30000 }).catch(() => {})
    ok('Agent turn started')

    // --- event-reactive driver ---------------------------------------------
    // Ground truth is the ATLAS editor behind the panel: a pathway analysis
    // page carrying both target and event cohorts means the build landed.
    const atlasText = () => page.evaluate(() => {
      const panel = document.querySelector('.plugin-overlay-panel')
      const prev = panel ? panel.style.display : null
      if (panel) panel.style.display = 'none'
      const t = document.body.innerText || ''
      if (panel && prev !== null) panel.style.display = prev
      return t
    }).catch(() => '')

    const analysisBuilt = async () => {
      const t = await atlasText()
      return /pathway/i.test(page.url()) &&
        /target cohort/i.test(t) && /event cohort/i.test(t)
    }

    const NEXT_INSTRUCTIONS = [
      'Looks good — go ahead and build it',
      'Now finish the whole pathway analysis without stopping: target cohort, event cohorts and the analysis settings',
      'continue',
    ]

    // Purposeful follow-ups instead of a wall of identical "continue" messages.
    const FOLLOW_UPS = [
      'Go ahead with the next step',
      'Please continue building the analysis',
      'Carry on — save what you have and wire up the pathway',
      'Finish the remaining steps',
      'Wrap it up when the analysis is complete',
    ]

    const DRIVE_BUDGET = 480000
    const t0 = Date.now()
    let acceptedByHand = 0, answered = 0, autoOn = false, said = 0, planCollapsed = false
    let decisions = 0, rejected = 0, groundingShown = false
    let askedToRun = false, ranAt = 0
    // accept, reject (the money shot: agent adapts), accept
    const DECISION_SCRIPT = ['accept', 'reject', 'accept']
    let idlePolls = 0, nudges = 0, quietUntil = 0, sessionError = null
    let done = false

    while (Date.now() - t0 < DRIVE_BUDGET && !done) {
      await beat(400)

      if (await errorAlert.isVisible().catch(() => false)) {
        sessionError = (await errorAlert.innerText().catch(() => '')) || 'unknown error'
        await caption('Pythia reported an error — stopping here')
        await beat(1500)
        break
      }

      if (await unsavedDialog.isVisible().catch(() => false)) {
        await caption('ATLAS asks before leaving unsaved work, keeping it')
        await beat(700)
        await page.getByRole('button', { name: /^cancel$/i }).first().click().catch(() => {})
        continue
      }

      if (!planCollapsed && (await planHeader.isVisible().catch(() => false))) {
        await caption('Pythia drafts a plan, collapsed here to follow the conversation')
        await beat(1500)
        planCollapsed = await collapsePlan()
        await beat(500)
        ok('Plan card collapsed for readability')
        continue
      }

      // Pythia asked a question — CLICK one of the options it offered.
      if (await askOptions.first().isVisible().catch(() => false)) {
        const question = await panel.locator('.ask-card .question').first().innerText().catch(() => '')
        await caption(`Pythia asks: ${question.replace(/\s+/g, ' ').slice(0, 70)}`)
        const n = await askOptions.count()
        let pick = askOptions.first()
        for (let i = 0; i < n; i++) {
          const label = await askOptions.nth(i).innerText().catch(() => '')
          if (/new|create|build|fresh|scratch/i.test(label)) { pick = askOptions.nth(i); break }
        }
        const label = (await pick.innerText().catch(() => '')).split('\n')[0]
        await beat(900)
        await pick.hover(); await beat(400)
        await pick.click()
        answered++
        await caption(`Answered: ${label.slice(0, 60)}`)
        await beat(900)
        quietUntil = Date.now() + 30000  // let it act on the answer unprompted
        continue
      }

      // Every artifact mutation is a proposal the researcher rules on. Show that
      // first and at length: accept, REJECT (and watch the agent adapt), accept.
      // Only then offer auto-approve, framed as the researcher pre-approving a
      // batch rather than the agent slipping out of review.
      if (await acceptBtn().isVisible().catch(() => false)) {
        if (decisions < DECISION_SCRIPT.length) {
          const decision = DECISION_SCRIPT[decisions]
          const card = (await panel.locator('.proposal-card').first().innerText().catch(() => '')) || ''
          const what = card.split('\n').filter(Boolean).slice(0, 2).join(' ').slice(0, 52)
          if (decision === 'reject') {
            await caption(`You reject this one${what ? ': ' + what : ''}`)
            const rejectBtn = panel.locator('button.reject, button.reject-all').first()
            await rejectBtn.scrollIntoViewIfNeeded().catch(() => {})
            await beat(1300)
            await rejectBtn.hover(); await beat(450)
            await rejectBtn.click()
            rejected++
            await beat(1100)
            await caption('Rejected. Pythia adapts instead of proceeding')
            await beat(1800)
          } else {
            await caption(`Pythia proposes a change, you approve it${what ? ': ' + what : ''}`)
            await acceptBtn().scrollIntoViewIfNeeded().catch(() => {})
            await beat(1200)
            await acceptBtn().hover(); await beat(400)
            await acceptBtn().click()
            acceptedByHand++
            await beat(1100)
          }
          decisions++
          continue
        }
        if (!autoOn) {
          await caption('The researcher can also pre-approve a batch: auto-approve')
          await autoApprove.hover(); await beat(500)
          await autoApprove.click()
          autoOn = await autoApprove.getAttribute('title').then(t => /On/i.test(t || '')).catch(() => false)
          await beat(900)
          continue
        }
        await acceptBtn().click().catch(() => {})
        continue
      }

      // Grounding: the concept searches are the claim that nothing comes from
      // model memory. Call it out the first time they appear.
      if (!groundingShown && (await panel.locator('text=/search_concepts/i').first().isVisible().catch(() => false))) {
        groundingShown = true
        await caption('Every concept comes from a vocabulary search, not model memory')
        await beat(2600)
        continue
      }

      if (await stopBtn.isVisible().catch(() => false)) { idlePolls = 0; continue }  // still streaming

      if (await analysisBuilt()) {
        if (!askedToRun) {
          askedToRun = true
          await caption('Asking Pythia to run it against the data')
          await say('Now run it against Eunomia')
          continue
        }
        // Break the moment the run actually starts rather than sitting on a
        // fixed timer with the chat panel open: that dead stretch between
        // generation and closing the panel is the dullest part of the video.
        if (ranAt === 0) { ranAt = Date.now(); continue }
        const runsNow = Number(psql(
          "select count(*) from webapi.batch_job_instance where job_name = 'generatePathwayAnalysis'")) || 0
        if (runsNow > baseRuns) { done = true; break }
        if (Date.now() - ranAt < 30000) continue
        done = true
        break
      }

      // Idle. Debounce before speaking: firing an instruction into the gap
      // between two turns derails the agent (and reads as spam on camera).
      idlePolls++
      if (idlePolls < 12) continue
      if (Date.now() < quietUntil) continue
      idlePolls = 0
      if (said < NEXT_INSTRUCTIONS.length) await say(NEXT_INSTRUCTIONS[said++])
      else if (nudges < 5) { await say('continue'); nudges++ }
      else break   // agent has genuinely stopped; keep reacting no further
    }

    if (answered > 0) ok('Answered Pythia by clicking its options', `${answered} question(s)`)
    if (acceptedByHand >= 1) ok('Proposals approved by hand', `${acceptedByHand} accepted on camera`)
    else fail('Proposals approved by hand', 'no proposal card appeared')
    if (rejected >= 1) ok('A proposal was rejected on camera', 'agent had to adapt')
    else fail('A proposal was rejected on camera', 'never got a second card to reject')
    if (autoOn) ok('Auto-approve enabled')
    if (sessionError) fail('Pythia session stayed healthy', sessionError.replace(/\s+/g, ' ').slice(0, 160))
    ok(done ? 'Pathway analysis assembled in ATLAS' : 'Driver finished', `${said} instruction(s)`)

    // --- reveal -------------------------------------------------------------
    if (await unsavedDialog.isVisible().catch(() => false)) {
      await page.getByRole('button', { name: /^cancel$/i }).first().click().catch(() => {})
      await beat(600)
    }
    await caption('The pathway analysis Pythia built, in ATLAS')
    await beat(900)
    // The enlarged panel sits on top of the FAB, so a hit-tested click lands on
    // the panel. Call the button's own click() so the overlay actually closes —
    // otherwise it covers half the results at the end.
    const closePanel = async () => {
      await page.evaluate(() => {
        const el = document.querySelector('[data-testid="plugin-fab-pythia-plugin"]')
        if (el instanceof HTMLElement) el.click()
      }).catch(() => {})
      await beat(800)
      return !(await page.locator('.plugin-overlay-panel').first().isVisible().catch(() => false))
    }
    let panelClosed = await closePanel()
    if (!panelClosed) panelClosed = await closePanel()
    if (panelClosed) ok('Chat panel closed for the reveal')
    else fail('Chat panel closed for the reveal', 'panel still covering the results')
    await beat(900)

    const onPathwayPage = /pathway/i.test(page.url())
    if (onPathwayPage) ok('Pathway analysis editor shown')
    else fail('Pathway analysis editor shown', `url=${page.url()}`)

    // Target/event cohorts live behind the collapsed design panel — open it so
    // the actual analysis Pythia assembled is on camera.
    const designToggle = page.getByRole('button', { name: /show analysis design/i }).first()
    if (await designToggle.isVisible().catch(() => false)) {
      await caption('Opening the analysis design Pythia assembled')
      await designToggle.hover(); await beat(700)
      await designToggle.click()
      await beat(2000)
    }
    const pageText = await atlasText()
    const hasCohorts = /target cohort/i.test(pageText) && /event cohort/i.test(pageText)
    if (hasCohorts) ok('Pathway analysis carries target + event cohorts')
    else fail('Pathway analysis carries target + event cohorts', 'no target/event cohorts on the page')

    // --- ask Pythia to read its own results --------------------------------
    // Building and running an analysis is only half the job; the point of
    // get_analysis_results is that it can tell you what came out.
    await caption('Asking Pythia to explain the results')
    await page.evaluate(() => {
      const el = document.querySelector('[data-testid="plugin-fab-pythia-plugin"]')
      if (el instanceof HTMLElement) el.click()
    }).catch(() => {})
    await panel.waitFor({ state: 'visible', timeout: 30000 }).catch(() => {})
    await beat(1200)
    await say('What do these results show?')
    // say() already waits for streaming to start; wait for it to stop.
    const explained = await (async () => {
      const t0 = Date.now()
      while (Date.now() - t0 < 120000) {
        if (!(await stopBtn.isVisible().catch(() => false))) return true
        await beat(1000)
      }
      return false
    })()
    await collapsePlan()
    await beat(1500)

    const transcript2 = (await panel.innerText().catch(() => '')) || ''
    if (/get_analysis_results/i.test(transcript2)) ok('Pythia read the results with get_analysis_results')
    else fail('Pythia read the results with get_analysis_results', 'tool was not called')
    if (explained && /%|persons?|patients?/i.test(transcript2)) ok('Pythia explained the numbers')
    else fail('Pythia explained the numbers', 'no quantitative explanation in the transcript')

    await caption('Pythia reads the analysis it just ran')
    await beat(3000)
    // close again for the final frame
    await page.evaluate(() => {
      const el = document.querySelector('[data-testid="plugin-fab-pythia-plugin"]')
      if (el instanceof HTMLElement) el.click()
    }).catch(() => {})
    await beat(1200)

    // --- did any of it actually persist? -----------------------------------
    const newPathway = psql(`select id||' | '||name from webapi.pathway_analysis where id > ${basePathway}`)
    const pwId = newPathway ? newPathway.split('|')[0].trim() : ''
    const targets = pwId ? psql(`select count(*) from webapi.pathway_target_cohort where pathway_analysis_id = ${pwId}`) : '0'
    const events = pwId ? psql(`select count(*) from webapi.pathway_event_cohort where pathway_analysis_id = ${pwId}`) : '0'

    // The agent may legitimately REUSE existing cohorts rather than build new
    // ones, so don't demand new rows. What must hold is that every cohort the
    // analysis references really exists and carries real concepts — that is
    // what makes the analysis meaningful rather than "any drug exposure".
    const refIds = pwId ? psql(
      `select string_agg(cohort_definition_id::text, ',') from (` +
      `select cohort_definition_id from webapi.pathway_target_cohort where pathway_analysis_id = ${pwId} ` +
      `union select cohort_definition_id from webapi.pathway_event_cohort where pathway_analysis_id = ${pwId}) x`) : ''
    const missing = refIds ? psql(
      `select count(*) from (select unnest(string_to_array('${refIds}', ',')::int[]) id) r ` +
      `where not exists (select 1 from webapi.cohort_definition c where c.id = r.id)`) : '1'
    const withConcepts = refIds ? psql(
      `select count(*) from webapi.cohort_definition_details d where d.id in (${refIds}) ` +
      `and (d.expression::jsonb #>> '{ConceptSets,0,expression,items,0,concept,CONCEPT_ID}') is not null`) : '0'
    const refCount = refIds ? refIds.split(',').length : 0

    if (newPathway && Number(targets) > 0 && Number(events) > 0) {
      ok('Pathway analysis persisted', `${newPathway.trim()} (${targets} target, ${events} event)`)
    } else {
      fail('Pathway analysis persisted', `pathway=${newPathway || 'none'} targets=${targets} events=${events}`)
    }

    if (refCount > 0 && Number(missing) === 0) ok('Every referenced cohort exists', `${refCount} cohorts`)
    else fail('Every referenced cohort exists', `${missing} of ${refCount} missing`)

    if (refCount > 0 && Number(withConcepts) === refCount) {
      ok('Referenced cohorts carry real concept ids', `${withConcepts}/${refCount}`)
    } else {
      fail('Referenced cohorts carry real concept ids', `only ${withConcepts}/${refCount} have concepts`)
    }

    // Pathway runs are Spring Batch jobs; count new generatePathwayAnalysis instances.
    const generated = psql(
      `select count(*) from webapi.batch_job_instance where job_name = 'generatePathwayAnalysis'`)
    const newRuns = Number(generated) - baseRuns
    if (newRuns > 0) ok('Pythia generated the analysis', `${newRuns} run(s) started`)
    else fail('Pythia generated the analysis', 'no generation was started')

    await page.evaluate(() => window.scrollTo({ top: 0, behavior: 'smooth' }))
    await beat(800)
    for (let i = 0; i < 5; i++) {
      await page.evaluate(() => window.scrollBy({ top: 320, behavior: 'smooth' }))
      await beat(750)
    }
    ok('Finished analysis reviewed on camera')

    // The run takes a few seconds against Eunomia. Wait for it to report
    // COMPLETED and let the sunburst render — the executed result is the whole
    // point ("it works on real data"), so it has to be on camera, not just
    // "generation started".
    await caption('The run completes against Eunomia')
    const runFinished = await page.locator('.workbench__sunburst-stage svg, .workbench__sunburst-stage canvas')
      .first().waitFor({ state: 'visible', timeout: 150000 }).then(() => true).catch(() => false)
    if (runFinished) ok('Run finished and results rendered')
    else fail('Run finished and results rendered', 'no results appeared')
    await beat(2500)

    // Bring the visualisation into frame and dwell on it.
    const sunburst = page.locator('.workbench__sunburst-stage svg, .workbench__sunburst-stage canvas').first()
    if (await sunburst.isVisible().catch(() => false)) {
      await sunburst.scrollIntoViewIfNeeded().catch(() => {})
      await caption('Treatment pathways for real Eunomia patients')
      await beat(4000)
      ok('Results visualisation shown')
    } else {
      await beat(2000)
      fail('Results visualisation shown', 'no chart rendered')
    }

    // --- did any of it actually persist? -----------------------------------
    const newCohorts = psql(`select count(*) from webapi.cohort_definition where id > ${baseCohort}`)

    await caption('')
    await card('Pythia', 'github.com/OHDSI/Pythia')
    await beat(3000)
  } catch (err) {
    fail('walkthrough', err && err.message ? err.message : err)
    await page.screenshot({ path: path.join(OUT, 'failure.png') }).catch(() => {})
  }

  const video = page.video()
  await ctx.close()
  await browser.close()

  let videoPath = null
  try { videoPath = await video.path() } catch {}
  const report = {
    videoPath, steps,
    consoleErrors: [...new Set(consoleErrors)].slice(0, 25),
    netFailures: [...new Set(netFailures)].slice(0, 25),
    allPassed: steps.every(s => s.ok),
  }
  fs.writeFileSync(path.join(OUT, 'report.json'), JSON.stringify(report, null, 2))
  console.log('\n=== REPORT ===')
  console.log('video:', videoPath)
  console.log('allPassed:', report.allPassed)
  console.log('consoleErrors:', report.consoleErrors.length, ' netFailures:', report.netFailures.length)
  process.exit(report.allPassed ? 0 : 1)
}

main()
