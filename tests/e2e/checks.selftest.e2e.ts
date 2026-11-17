import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { test, expect } from './harness'
import { compareToReference, expectHealthyConversation } from './checks'
import { ChatTrace } from './trace'
import type { CohortExpression } from './webapi'

// Tests for the checks, not for Pythia: a check that never fails proves
// nothing, so each one is shown a good and a bad input. Needs no model, only
// WebAPI for the reference comparison. Runs in seconds.

const sse = (...events: object[]) => events.map(e => `data: ${JSON.stringify(e)}`).join('\n') + '\ndata: [DONE]\n'
const call = (id: string, toolName: string, input: object) => ({ type: 'tool-input-available', toolCallId: id, toolName, input })
const out = (id: string, output: unknown) => ({ type: 'tool-output-available', toolCallId: id, output })
const clientResult = (id: string, name: string, input: object, output: object) =>
  ({ type: `tool-${name}`, toolCallId: id, state: 'output-available', input, output })
const accepted = { decision: 'accepted' }
const strict = { soft: false }

// A conversation: server stream first, then the client's answers to the
// proposals as they appear in the next request's history.
function conversation(stream: object[], answers: object[]): ChatTrace {
  const t = new ChatTrace()
  t.ingestStream(sse(...stream))
  t.ingestRequest({ messages: [{ role: 'assistant', parts: answers }] })
  return t
}

const search = [
  call('s1', 'search_concepts', { query: 'gastrointestinal hemorrhage' }),
  out('s1', { results: [{ conceptId: 192671, conceptName: 'Gastrointestinal hemorrhage' }] }),
]
const entry = { conceptId: 192671, conceptName: 'Gastrointestinal hemorrhage', domain: 'Condition', includeDescendants: true }
const savedExpr = {
  ConceptSets: [{ id: 0, name: 'GI', expression: { items: [{ concept: { CONCEPT_ID: 192671 }, includeDescendants: true }] } }],
  PrimaryCriteria: { CriteriaList: [{ ConditionOccurrence: { CodesetId: 0 } }] },
} as unknown as CohortExpression

function good(): ChatTrace {
  return conversation(
    [...search, call('p1', 'set_entry_event', entry), call('p2', 'save_cohort', { name: 'GI' }), call('p3', 'generate_analysis', { analysisType: 'cohort', analysisId: 5 })],
    [clientResult('p1', 'set_entry_event', entry, accepted), clientResult('p2', 'save_cohort', {}, { decision: 'accepted', savedId: 5 }),
      clientResult('p3', 'generate_analysis', {}, accepted)],
  )
}

test.describe('conversation checks', () => {
  test('pass a well-formed build', () => {
    const t = good()
    expect(t.ordered().map(c => c.name)).toEqual(['search_concepts', 'set_entry_event', 'save_cohort', 'generate_analysis'])
    expectHealthyConversation(t, savedExpr, strict)
  })

  const gate = { instruction: 'Step "Add criteria" (add-criteria) must complete before this one. Do that step first.' }
  const window = { priorDays: 365, postDays: 0 }
  test('pass a proposal the plan gate refused and the agent then redid', () => {
    expectHealthyConversation(conversation(
      [...search, call('p1', 'set_entry_event', entry), call('g1', 'set_observation_window', window), call('g2', 'set_observation_window', window), call('p2', 'save_cohort', {})],
      [clientResult('p1', 'set_entry_event', entry, accepted), clientResult('g1', 'set_observation_window', window, gate),
        clientResult('g2', 'set_observation_window', window, accepted), clientResult('p2', 'save_cohort', {}, accepted)],
    ), savedExpr, strict)
  })
  test('fail on a gate-refused proposal that is never applied', () => {
    expect(() => expectHealthyConversation(conversation(
      [...search, call('p1', 'set_entry_event', entry), call('g1', 'set_observation_window', window), call('p2', 'save_cohort', {})],
      [clientResult('p1', 'set_entry_event', entry, accepted), clientResult('g1', 'set_observation_window', window, gate), clientResult('p2', 'save_cohort', {}, accepted)],
    ), savedExpr, strict)).toThrow(/never applied afterwards/)
  })

  const bad: [string, () => ChatTrace, RegExp][] = [
    ['an ungrounded concept id', () => conversation(
      [...search, call('p1', 'set_entry_event', { ...entry, conceptId: 4000001 }), call('p2', 'save_cohort', {})],
      [clientResult('p1', 'set_entry_event', {}, accepted), clientResult('p2', 'save_cohort', {}, accepted)],
    ), /concept ids no tool had returned/],
    ['a rejected proposal', () => conversation(
      [...search, call('p1', 'set_entry_event', entry), call('p2', 'save_cohort', {})],
      [clientResult('p1', 'set_entry_event', entry, { decision: 'rejected' }), clientResult('p2', 'save_cohort', {}, accepted)],
    ), /not accepted/],
    ['a proposal ATLAS failed to apply', () => conversation(
      [...search, call('p1', 'set_entry_event', entry), call('p2', 'save_cohort', {})],
      [clientResult('p1', 'set_entry_event', entry, { decision: 'accepted', applied: false, error: 'x' }), clientResult('p2', 'save_cohort', {}, accepted)],
    ), /not accepted or not applied/],
    ['editing before any lookup', () => conversation(
      [call('p1', 'set_entry_event', entry), ...search, call('p2', 'save_cohort', {})],
      [clientResult('p1', 'set_entry_event', entry, accepted), clientResult('p2', 'save_cohort', {}, accepted)],
    ), /no vocabulary\/library lookup/],
    ['saving before the entry event', () => conversation(
      [...search, call('p2', 'save_cohort', {}), call('p1', 'set_entry_event', entry)],
      [clientResult('p1', 'set_entry_event', entry, accepted), clientResult('p2', 'save_cohort', {}, accepted)],
    ), /save_cohort came before/],
    ['the same edit accepted twice', () => conversation(
      [...search, call('p1', 'set_entry_event', entry), call('p9', 'set_entry_event', entry), call('p2', 'save_cohort', {})],
      [clientResult('p1', 'set_entry_event', entry, accepted), clientResult('p9', 'set_entry_event', entry, accepted), clientResult('p2', 'save_cohort', {}, accepted)],
    ), /accepted twice/],
    ['a failing server tool', () => conversation(
      [...search, call('e1', 'search_concepts', { query: 'x' }), { type: 'tool-output-error', toolCallId: 'e1', errorText: 'HTTP 500' },
        call('p1', 'set_entry_event', entry), call('p2', 'save_cohort', {})],
      [clientResult('p1', 'set_entry_event', entry, accepted), clientResult('p2', 'save_cohort', {}, accepted)],
    ), /server tools failed/],
    ['a stream error', () => { const t = good(); t.ingestStream(sse({ type: 'error', errorText: 'An error occurred.' })); return t }, /stream reported errors/],
  ]
  for (const [name, make, message] of bad) {
    test(`fail on ${name}`, () => {
      expect(() => expectHealthyConversation(make(), savedExpr, strict)).toThrow(message)
    })
  }

  test('fail on a saved concept the conversation never surfaced', () => {
    const stray = structuredClone(savedExpr)
    stray.ConceptSets[0].expression.items.push({ concept: { CONCEPT_ID: 4000002 } })
    expect(() => expectHealthyConversation(good(), stray, strict)).toThrow(/never appeared in the conversation/)
  })

  test('read concept ids from tool outputs wrapped as JSON text', () => {
    const t = new ChatTrace()
    t.ingestStream(sse(call('s', 'get_reference_phenotype', { cohortId: 1 }),
      out('s', { content: [{ type: 'text', text: JSON.stringify({ ConceptSets: [{ expression: { items: [{ concept: { CONCEPT_ID: 4283893 } }] } }] }) }] })))
    expect(t.groundedConceptIds().has(4283893)).toBe(true)
  })
})

// The reference comparison against real Eunomia data: a cohort with only
// viral pharyngitis must fail against the library's pharyngitis (which also
// covers streptococcal sore throat), and the reference itself must pass.
test.describe('reference comparison', () => {
  const pharyngitis = JSON.parse(readFileSync(fileURLToPath(new URL('./fixtures/phenotype-library/327.json', import.meta.url)), 'utf8')) as CohortExpression
  const thresholds = { minRecall: 1, minPrecision: 0.5, minPersonJaccard: 0.8 }

  async function saved(api: import('./webapi').WebApi, expr: CohortExpression) {
    const id = await api.createCohort(`e2e selftest ${Date.now()}`, expr)
    return { id, expr }
  }

  test('pass the reference against itself', async ({ api, sourceKey }) => {
    const built = await saved(api, pharyngitis)
    try {
      await compareToReference(api, sourceKey, built, { id: 327, name: 'Pharyngitis', expr: pharyngitis }, thresholds, strict)
    } finally { await api.deleteCohort(built.id) }
  })

  test('fail a cohort that misses part of the phenotype', async ({ api, sourceKey }) => {
    const narrow = structuredClone(pharyngitis)
    narrow.ConceptSets[0].expression.items = [{ concept: { CONCEPT_ID: 4112343, CONCEPT_NAME: 'Acute viral pharyngitis' }, includeDescendants: true }]
    const built = await saved(api, narrow)
    try {
      await expect(compareToReference(api, sourceKey, built, { id: 327, name: 'Pharyngitis', expr: pharyngitis }, thresholds, strict))
        .rejects.toThrow(/miss reference concepts 28060/)
    } finally { await api.deleteCohort(built.id) }
  })

  test('fail a cohort that finds different people', async ({ api, sourceKey }) => {
    // Right concepts on paper, but limited to people over 60: recall and
    // precision pass, so only the person-level check can catch it.
    const older = structuredClone(pharyngitis) as any
    older.InclusionRules = [{ name: 'over 60', expression: { Type: 'ALL', CriteriaList: [], DemographicCriteriaList: [{ Age: { Value: 60, Op: 'gt' } }], Groups: [] } }]
    const built = await saved(api, older)
    try {
      await expect(compareToReference(api, sourceKey, built, { id: 327, name: 'Pharyngitis', expr: pharyngitis }, thresholds, strict))
        .rejects.toThrow(/disagree on who qualifies/)
    } finally { await api.deleteCohort(built.id) }
  })
})
