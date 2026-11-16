import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { test, expect } from './harness'
import { compareToReference, expectGrounded, expectHealthyConversation } from './checks'
import type { CohortExpression } from './webapi'

// Pythia's cohorts scored against validated OHDSI Phenotype Library
// definitions (fixtures/phenotype-library, pinned to v3.37.0, the tag the
// agent's get_reference_phenotype reads).
//
// Pythia can look these phenotypes up itself, which would make a plain
// comparison circular: a copied phenotype trivially matches. So there are two
// kinds of scenario:
//   - reuse: the request invites a library phenotype. Checks that Pythia finds
//     one and carries it into ATLAS faithfully.
//   - blind: the request rules the library out, the trace proves it was not
//     consulted, and the cohort Pythia built from the vocabulary is scored
//     against the hidden reference.
// All references were checked to generate on Eunomia (479 to 2,689 persons).

const LIBRARY_TAG = 'v3.37.0'
const fixture = (id: number): CohortExpression =>
  JSON.parse(readFileSync(fileURLToPath(new URL(`./fixtures/phenotype-library/${id}.json`, import.meta.url)), 'utf8'))

// Tools that would hand the agent the reference (or another curated
// definition) instead of making it choose concepts itself.
const LIBRARY_TOOLS = ['get_reference_phenotype', 'search_phenotypes', 'use_concept_set', 'search_existing_concept_sets']

const BLIND: {
  id: number; name: string; ask: string; minRecall: number; minPrecision: number; minPersonJaccard: number
}[] = [
  // On Eunomia, 4 concepts: sinusitis, chronic, acute bacterial, viral. Chronic
  // sinusitis in a "sinusitis" cohort is defensible either way, hence 0.75.
  { id: 368, name: 'Sinusitis', ask: 'patients with sinusitis', minRecall: 0.75, minPrecision: 0.75, minPersonJaccard: 0.8 },
  // Resolves to acute viral pharyngitis AND streptococcal sore throat; a
  // cohort with only the viral one misses half of pharyngitis.
  { id: 327, name: 'Pharyngitis', ask: 'patients with pharyngitis', minRecall: 1, minPrecision: 0.5, minPersonJaccard: 0.8 },
  { id: 533, name: 'Otitis media', ask: 'patients with otitis media', minRecall: 1, minPrecision: 0.5, minPersonJaccard: 0.8 },
  { id: 417, name: 'Gastrointestinal hemorrhage', ask: 'patients with gastrointestinal hemorrhage', minRecall: 1, minPrecision: 0.5, minPersonJaccard: 0.8 },
]

for (const ref of BLIND) {
  test(`blind build matches library phenotype #${ref.id} (${ref.name})`, async ({ build, api, sourceKey }) => {
    const { cohort, trace } = await build(
      `Create a new cohort of ${ref.ask}, counting every occurrence. Build it yourself from the vocabulary: ` +
        'do not use the phenotype library, existing cohorts or existing concept sets. Save it and generate it ' +
        'on the current data source.',
    )

    // Contamination guard: a cohort assembled from a library definition says
    // nothing about the agent's own concept choices.
    const consulted = trace.named(...LIBRARY_TOOLS).map(c => c.name)
    expect(consulted, 'the agent consulted curated definitions despite being told not to').toEqual([])

    expect(cohort, 'no cohort was saved').toBeTruthy()
    expectHealthyConversation(trace, cohort!.expr)
    await expectGrounded(api, sourceKey, cohort!.expr)
    await compareToReference(api, sourceKey, cohort!, { id: ref.id, name: ref.name, expr: fixture(ref.id) }, ref)
  })
}

test('reuse: a validated library phenotype is found and carried into ATLAS faithfully', async ({ build, api, sourceKey }) => {
  const { cohort, trace } = await build(
    'Create a cohort of new episodes of acute bronchitis. Use a validated phenotype from the OHDSI Phenotype ' +
      'Library if one fits. Save it and generate it on the current data source.',
  )

  const fetched = trace.named('get_reference_phenotype').filter(c => c.output !== undefined && !c.error)
  expect(fetched.length, 'the agent did not fetch a library phenotype').toBeGreaterThan(0)
  expect(cohort, 'no cohort was saved').toBeTruthy()
  expectHealthyConversation(trace, cohort!.expr)

  // Compare with the phenotype it actually adopted (the last one fetched),
  // read from the same pinned library tag the agent reads.
  const chosen = Number((fetched.at(-1)!.input as { cohortId?: unknown })?.cohortId)
  expect(Number.isInteger(chosen), `could not tell which phenotype was fetched: ${JSON.stringify(fetched.at(-1)!.input)}`).toBe(true)
  const res = await fetch(`https://raw.githubusercontent.com/OHDSI/PhenotypeLibrary/${LIBRARY_TAG}/inst/cohorts/${chosen}.json`)
  expect(res.ok, `library phenotype ${chosen} not available at ${LIBRARY_TAG}`).toBe(true)
  const reference = (await res.json()) as CohortExpression
  test.info().annotations.push({ type: 'reuse', description: `adopted library phenotype #${chosen}` })

  // Importing is a copy, so the bar is equality on the data: same index
  // concepts, nothing the reference excludes, (almost) the same people.
  await compareToReference(api, sourceKey, cohort!, { id: chosen, name: `library #${chosen}`, expr: reference },
    { minRecall: 1, minPrecision: 1, minPersonJaccard: 0.95 })
})
