import { expect, test } from '@playwright/test'
import { collectConceptIds, decisionOf, type ToolCall, type ChatTrace } from './trace'
import { allConceptIds, codesetItems, type CohortExpression, type WebApi } from './webapi'
import { cohortSubjects, jaccard, resultsDbAvailable } from './results-db'

// Client-side tools that change the open artifact. Mirrors CLIENT_SIDE_TOOLS in
// src/chat-session.ts minus the ones that do not edit (navigate, ask).
export const PROPOSAL_TOOLS = new Set([
  'add_criterion', 'add_criteria', 'set_entry_event', 'set_observation_window', 'add_exit_criterion',
  'set_censor_event', 'add_inclusion_rule', 'save_cohort', 'create_standalone_concept_set',
  'add_demographic_criterion', 'set_event_limits', 'add_qualifying_criterion', 'set_censor_window',
  'set_era_collapse', 'use_concept_set', 'remove_inclusion_rule', 'remove_entry_event', 'generate_analysis',
  'update_concept_set',
])

// Tools whose output can legitimately be the source of a concept id.
const LOOKUP_TOOLS = new Set([
  'search_concepts', 'verify_concept_mapping', 'get_reference_phenotype', 'search_phenotypes',
  'search_existing_concept_sets', 'search_existing_cohorts', 'get_artifact', 'phenotype_patterns',
])

function isGateRefusal(c: ToolCall): boolean {
  const o = c.output as { instruction?: unknown } | null
  return !!o && typeof o.instruction === 'string' && /must complete before this one/i.test(o.instruction)
}

const fmt = (c: ToolCall) => `#${c.seq} ${c.name} ${JSON.stringify(c.input ?? {}).slice(0, 200)}`

// How the agent got to the cohort, from the recorded conversation.
export function expectHealthyConversation(
  trace: ChatTrace, expr: CohortExpression | undefined, { soft = true }: { soft?: boolean } = {},
) {
  // Soft by default, so one agent run reports every finding instead of the
  // first. The self-test runs them strict to see each one fail.
  const e = soft ? expect.soft : expect
  const calls = trace.ordered()
  const proposals = calls.filter(c => PROPOSAL_TOOLS.has(c.name))

  e(trace.streamErrors, 'the agent stream reported errors').toEqual([])

  const failed = calls.filter(c => c.side === 'server' && c.error)
  e(failed.map(c => `${fmt(c)} -> ${c.error}`), 'server tools failed').toEqual([])

  // Every proposal was answered, accepted, and actually applied by ATLAS.
  const unresolved = proposals.filter(c => c.output === undefined && !c.error)
  e(unresolved.map(fmt), 'proposals left without a result').toEqual([])
  // A proposal the plan's step-order gate turned back carries only an
  // instruction ("do step X first"). Recovering from that is fine; a refused
  // change that is never applied later is not.
  const gated = proposals.filter(c => c.output !== undefined && decisionOf(c) === undefined && isGateRefusal(c))
  const neverApplied = gated.filter(g => !proposals.some(p => p.seq > g.seq && p.name === g.name && decisionOf(p) === 'accepted'))
  e(neverApplied.map(fmt), 'proposals refused by the plan gate and never applied afterwards').toEqual([])
  if (gated.length) test.info().annotations.push({ type: 'gate', description: `${gated.length} proposal(s) sent out of plan order and corrected: ${gated.map(fmt).join('; ')}` })
  const notAccepted = proposals.filter(c => c.output !== undefined && decisionOf(c) !== 'accepted' && !gated.includes(c))
  e(notAccepted.map(c => `${fmt(c)} -> ${decisionOf(c) ?? JSON.stringify(c.output).slice(0, 200)}`),
    'proposals not accepted or not applied by ATLAS').toEqual([])

  // Research before editing: some lookup precedes the first edit.
  const firstEdit = proposals.find(c => c.name !== 'generate_analysis')
  e(firstEdit, 'the agent never proposed an edit').toBeTruthy()
  if (firstEdit) e(calls.some(c => LOOKUP_TOOLS.has(c.name) && c.seq < firstEdit.seq),
    `no vocabulary/library lookup before the first edit (${fmt(firstEdit)})`).toBe(true)

  // Build, then save, then generate.
  const entry = trace.first('set_entry_event') ?? trace.first('use_concept_set')
  const save = trace.first('save_cohort')
  e(save, 'the agent never proposed save_cohort').toBeTruthy()
  if (entry && save) e(save.seq, 'save_cohort came before the entry event').toBeGreaterThan(entry.seq)
  const gen = trace.first('generate_analysis')
  if (gen && save) e(gen.seq, 'generate_analysis came before save_cohort').toBeGreaterThan(save.seq)

  // Grounding, per proposal: every concept id it uses was returned by a lookup
  // earlier in the conversation, not recalled from model memory.
  const ungrounded: string[] = []
  for (const p of proposals) {
    const used = collectConceptIds(p.input, new Set<number>())
    if (!used.size) continue
    const seen = trace.groundedConceptIds(p.seq)
    const missing = [...used].filter(id => !seen.has(id))
    if (missing.length) ungrounded.push(`${fmt(p)} uses ${missing.join(', ')}`)
  }
  e(ungrounded, 'proposals used concept ids no tool had returned').toEqual([])

  // No edit accepted twice: re-proposing an applied change duplicates it.
  const seenEdits = new Map<string, ToolCall>()
  const dupes: string[] = []
  for (const p of proposals) {
    if (['save_cohort', 'generate_analysis'].includes(p.name) || decisionOf(p) !== 'accepted') continue
    const key = `${p.name}:${JSON.stringify(p.input)}`
    if (seenEdits.has(key)) dupes.push(`${fmt(p)} repeats #${seenEdits.get(key)!.seq}`)
    else seenEdits.set(key, p)
  }
  e(dupes, 'the same edit was accepted twice').toEqual([])

  // The saved cohort contains only concepts the conversation surfaced.
  if (expr) {
    const seen = trace.groundedConceptIds()
    for (const p of proposals) collectConceptIds(p.input, seen)
    const usedSavedSet = proposals.some(p => p.name === 'use_concept_set')
    const stray = allConceptIds(expr).filter(id => !seen.has(id))
    if (usedSavedSet) {
      test.info().annotations.push({ type: 'note', description: `reused a saved concept set; concepts not shown in the conversation: ${stray.join(', ') || 'none'}` })
    } else {
      e(stray, 'the saved cohort contains concepts that never appeared in the conversation').toEqual([])
    }
  }
}

// Every concept is a real, valid vocabulary concept and every included one is
// standard.
export async function expectGrounded(api: WebApi, sourceKey: string, expr: CohortExpression) {
  for (const id of allConceptIds(expr)) {
    const c = await api.concept(sourceKey, id).catch(() => undefined)
    expect(c, `concept ${id} is not in the ${sourceKey} vocabulary`).toBeTruthy()
    expect(c!.INVALID_REASON ?? 'V', `concept ${id} (${c!.CONCEPT_NAME}) is invalid`).toBe('V')
  }
  for (const cs of expr.ConceptSets) {
    for (const item of cs.expression.items.filter(i => !i.isExcluded)) {
      const c = await api.concept(sourceKey, item.concept.CONCEPT_ID)
      expect(c.STANDARD_CONCEPT, `${c.CONCEPT_ID} ${c.CONCEPT_NAME} in "${cs.name}" is not standard`).toBe('S')
    }
  }
}

// The resolved concepts behind an expression's index event, on the source's
// vocabulary (descendants expanded, exclusions removed).
export async function resolvedIndexConcepts(api: WebApi, sourceKey: string, expr: CohortExpression): Promise<Set<number>> {
  const ids = new Set<number>()
  const codesets = new Set<unknown>()
  for (const crit of expr.PrimaryCriteria.CriteriaList) {
    for (const body of Object.values(crit)) {
      if (body && typeof body === 'object' && 'CodesetId' in body) codesets.add((body as { CodesetId: unknown }).CodesetId)
    }
  }
  for (const id of codesets) {
    const items = codesetItems(expr, id)
    if (items.length) for (const c of await api.resolveConceptSet(sourceKey, { items })) ids.add(c)
  }
  return ids
}

export interface ReferenceThresholds {
  // Share of the reference's index concepts the agent's index event covers.
  minRecall: number
  // Share of the agent's index concepts the reference also has.
  minPrecision: number
  // Person-level agreement of the two generated cohorts.
  minPersonJaccard: number
}

// Compares a cohort Pythia built against a validated library phenotype: the
// concept ids behind the index event, the concepts the reference excludes, and
// who ends up in each cohort when both are generated on the same data.
export async function compareToReference(
  api: WebApi, sourceKey: string, built: { id: number; expr: CohortExpression },
  reference: { id: number; name: string; expr: CohortExpression }, t: ReferenceThresholds,
  { soft = true }: { soft?: boolean } = {},
) {
  const e = soft ? expect.soft : expect
  const mine = await resolvedIndexConcepts(api, sourceKey, built.expr)
  const ref = await resolvedIndexConcepts(api, sourceKey, reference.expr)
  expect(ref.size, `reference ${reference.id} resolves to no concepts on ${sourceKey}`).toBeGreaterThan(0)
  const both = [...mine].filter(x => ref.has(x))
  const recall = both.length / ref.size
  const precision = mine.size ? both.length / mine.size : 0
  const missing = [...ref].filter(x => !mine.has(x))
  const extra = [...mine].filter(x => !ref.has(x))
  test.info().annotations.push({
    type: 'concepts',
    description: `vs library #${reference.id} "${reference.name}": recall=${recall.toFixed(2)} precision=${precision.toFixed(2)} missing=[${missing}] extra=[${extra}]`,
  })
  e(recall, `index concepts miss reference concepts ${missing.join(', ')}`).toBeGreaterThanOrEqual(t.minRecall)
  e(precision, `index concepts add concepts the reference does not have: ${extra.join(', ')}`).toBeGreaterThanOrEqual(t.minPrecision)

  // Concepts the reference explicitly excludes must not be in the index event.
  const refExcluded = new Set<number>()
  for (const cs of reference.expr.ConceptSets) {
    const items = cs.expression.items.filter(i => i.isExcluded).map(i => ({ ...i, isExcluded: false }))
    if (items.length) for (const c of await api.resolveConceptSet(sourceKey, { items })) refExcluded.add(c)
  }
  const leaked = [...mine].filter(x => refExcluded.has(x))
  e(leaked, 'index event includes concepts the reference phenotype excludes').toEqual([])

  // Same people? Generate the reference next to the built cohort and compare.
  if (!resultsDbAvailable(sourceKey)) {
    test.info().annotations.push({ type: 'note', description: 'results table not reachable; person-level comparison skipped' })
    return
  }
  const refId = await api.createCohort(`e2e reference ${reference.id} ${Date.now()}`, reference.expr)
  try {
    await api.generateAndWait(refId, sourceKey)
    await api.generateAndWait(built.id, sourceKey)
    const a = cohortSubjects(sourceKey, built.id)
    const b = cohortSubjects(sourceKey, refId)
    const j = jaccard(a, b)
    test.info().annotations.push({ type: 'persons', description: `built=${a.size} reference=${b.size} jaccard=${j.toFixed(3)}` })
    e(j, `built cohort (${a.size} persons) and reference (${b.size} persons) disagree on who qualifies`).toBeGreaterThanOrEqual(t.minPersonJaccard)
  } finally {
    if (!process.env.PYTHIA_E2E_KEEP) await api.deleteCohort(refId)
  }
}
