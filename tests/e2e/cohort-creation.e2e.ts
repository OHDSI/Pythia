import { test, expect } from './harness'
import { expectGrounded, expectHealthyConversation, resolvedIndexConcepts } from './checks'
import { allCriteria, codesetItems } from './webapi'

// Cohorts with no library counterpart, so reuse is impossible and the agent
// has to compose the definition. Expected concept ids are hand-checked against
// Eunomia's vocabulary, as resolved sets (descendants expanded):
//   gastrointestinal hemorrhage -> {192671}
//   ibuprofen (ingredient + descendants) -> {1177480, 19019979, 19078461}
// The combined scenario's hand-built reference finds 7 people on Eunomia.

const GI_HEMORRHAGE = [192671]
const IBUPROFEN = [1177480, 19019979, 19078461]

const sorted = (s: Iterable<number>) => [...s].sort((a, b) => a - b)

test('builds, saves and generates a cohort from a plain request', async ({ build, api, sourceKey }) => {
  const { cohort, trace } = await build(
    'Create a new cohort of patients with gastrointestinal hemorrhage, save it and generate it on the ' +
      'current data source. Assume no existing cohort fits.',
  )
  expect(cohort, 'no cohort was saved').toBeTruthy()
  const { expr } = cohort!
  expectHealthyConversation(trace, expr)
  await expectGrounded(api, sourceKey, expr)

  expect(sorted(await resolvedIndexConcepts(api, sourceKey, expr)), 'index event concepts').toEqual(GI_HEMORRHAGE)

  const gen = await api.generateAndWait(cohort!.id, sourceKey)
  expect(gen.status, gen.failMessage ?? '').toMatch(/COMPLETE/i)
  expect(gen.personCount ?? 0).toBeGreaterThan(0)
})

test('builds a cohort with an age limit and a prior-exposure inclusion rule', async ({ build, api, sourceKey }) => {
  const { cohort, trace } = await build(
    'Create a new cohort: adults aged 18 or older with gastrointestinal hemorrhage who had at least one ' +
      'ibuprofen exposure in the 365 days before the hemorrhage. Save it and generate it on the current data ' +
      'source. Assume no existing cohort fits.',
  )
  expect(cohort, 'no cohort was saved').toBeTruthy()
  const { expr } = cohort!
  expectHealthyConversation(trace, expr)
  await expectGrounded(api, sourceKey, expr)

  expect(sorted(await resolvedIndexConcepts(api, sourceKey, expr)), 'index event concepts').toEqual(GI_HEMORRHAGE)

  // The ibuprofen requirement: a drug criterion outside the index event whose
  // concept set resolves to exactly ibuprofen and its products.
  const drug = allCriteria([expr.InclusionRules ?? [], expr.AdditionalCriteria ?? null])
    .filter(([domain]) => domain === 'DrugExposure' || domain === 'DrugEra')
  expect(drug.length, `no drug criterion outside the index event: ${JSON.stringify(expr.InclusionRules)}`).toBeGreaterThan(0)
  const resolvedDrugSets = await Promise.all(drug.map(async ([, c]) => {
    const items = codesetItems(expr, c.CodesetId)
    return items.length ? sorted(await api.resolveConceptSet(sourceKey, { items })) : []
  }))
  expect(resolvedDrugSets, 'ibuprofen criterion concepts').toContainEqual(IBUPROFEN)

  // The age limit, wherever it is encoded. Age >= 18 or > 17 both qualify.
  const ages = JSON.stringify(expr).match(/"Age":\{[^}]*\}/g) ?? []
  expect(ages.some(a => (/"Value":18\b/.test(a) && /"Op":"gte"/.test(a)) || (/"Value":17\b/.test(a) && /"Op":"gt"/.test(a))),
    `no age >= 18 criterion found (Age clauses: ${ages.join(', ') || 'none'})`).toBe(true)

  const gen = await api.generateAndWait(cohort!.id, sourceKey)
  expect(gen.status, gen.failMessage ?? '').toMatch(/COMPLETE/i)
  expect(gen.personCount ?? 0).toBeGreaterThan(0)
})
