import { test as base, expect } from '@playwright/test'
import { PythiaPanel, type DriveResult } from './pythia-driver'
import { ChatTrace } from './trace'
import { WebApi, type CohortExpression, type CohortSummary } from './webapi'

// Shared setup for the e2e scenarios: a WebAPI client, and `build(prompt)`,
// which runs one full Pythia session in ATLAS and returns the cohort it saved
// together with the recorded conversation. Every cohort created during a test
// (by Pythia or by the test itself) is deleted afterwards unless
// PYTHIA_E2E_KEEP is set; its definition is attached to the report first.

export interface Built {
  cohort: (CohortSummary & { expr: CohortExpression }) | undefined
  trace: ChatTrace
  run: DriveResult
}

interface Fixtures {
  api: WebApi
  sourceKey: string
  build: (prompt: string) => Promise<Built>
}

export const test = base.extend<Fixtures>({
  api: async ({}, use) => { await use(await WebApi.login()) },

  sourceKey: async ({ api }, use) => { await use(await api.sourceKey()) },

  build: async ({ page, api, sourceKey }, use, testInfo) => {
    const baseline = await api.maxCohortId()

    // The newest cohort saved since the test started that has an index event.
    // An earlier save in the same chat is superseded by a later one.
    const latest = async () => {
      for (const c of (await api.cohortsAfter(baseline)).reverse()) {
        const expr = await api.expression(c.id).catch(() => undefined)
        if (expr?.PrimaryCriteria?.CriteriaList?.length) return { ...c, expr }
      }
      return undefined
    }

    // Done once that cohort has finished (or failed) generating: the prompt has
    // Pythia propose generate_analysis right after a save.
    const done = async () => {
      const c = await latest()
      if (!c) return false
      const gen = await api.generation(c.id, sourceKey).catch(() => undefined)
      return !!gen && /COMPLETE|FAILED/i.test(gen.status)
    }

    await use(async (prompt: string) => {
      const trace = ChatTrace.attach(page)
      const pythia = await PythiaPanel.open(page)
      await pythia.send(prompt)
      let run: DriveResult
      try {
        run = await pythia.drive({ done, preferOption: /new|create|build|scratch|library|phenotype/i })
      } finally {
        await trace.settle()
        await testInfo.attach('tool-calls.txt', { body: trace.summary() || '(none)', contentType: 'text/plain' })
        await testInfo.attach('trace.json', { body: JSON.stringify(trace, null, 2), contentType: 'application/json' })
      }
      await testInfo.attach('transcript.txt', { body: run.transcript, contentType: 'text/plain' })
      testInfo.annotations.push({
        type: 'metrics',
        description: `requests=${trace.requests} toolCalls=${trace.calls.size} questions=${run.questionsAnswered} nudges=${run.nudgesSent}`,
      })
      return { cohort: await latest(), trace, run }
    })

    for (const c of await api.cohortsAfter(baseline)) {
      await testInfo.attach(`cohort-${c.id}.json`, {
        body: JSON.stringify({ ...c, expression: await api.expression(c.id).catch(() => null) }, null, 2),
        contentType: 'application/json',
      })
      if (!process.env.PYTHIA_E2E_KEEP) await api.deleteCohort(c.id)
    }
  },
})

export { expect }
