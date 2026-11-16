# Cohort creation e2e

Live end-to-end tests of the conversation with the LLM that builds a cohort.
A researcher asks Pythia for a cohort in the ATLAS UI. The test records the
whole conversation and checks how the agent got there and whether the saved
cohort has the right concepts and the right patients.

The eve evals in `agent/plugin/evals/` can't check this. Proposal tools
(`set_entry_event`, `add_criteria`, `save_cohort`, …) run on the client, and
`eve eval` has no way to answer them, so every eval stops at the first pending
proposal. Only ATLAS's cohort store applies proposals, so this suite runs the
real UI with **auto-approve** on, which accepts each proposal the same way a
click on Accept does.

## What is checked

**The conversation** (`trace.ts`, `checks.ts`). The test records the `/chat`
traffic: tool calls and server results from the response stream, and the
panel's answers to proposals from the next request. On that record it checks:

- there are no stream errors and no failed server tools
- every proposal was accepted and actually applied by ATLAS
- some vocabulary or library lookup happened before the first edit, and the
  steps ran in order: entry event, then save, then generate
- every concept id in a proposal was returned by an earlier tool call, not
  recalled from model memory
- the same edit was never accepted twice
- the saved cohort contains only concepts that appeared in the conversation

**The result**, read from WebAPI. The page can't be trusted here: the editor
shows unsaved cohorts too.

- the cohort was saved and its concepts are real, valid, standard vocabulary
  concepts
- **no library counterpart** (`cohort-creation.e2e.ts`): the concepts behind
  the index event and the ibuprofen criterion must resolve to exactly the
  expected ids on Eunomia, the age limit must be encoded, and generating the
  cohort finds patients
- **against validated phenotypes** (`reference-phenotypes.e2e.ts`): the cohort
  is compared with an OHDSI Phenotype Library definition (fixtures pinned to
  v3.37.0, the tag the agent reads). The comparison covers recall and precision
  of the resolved index concepts, the concepts the reference excludes, and the
  Jaccard overlap of the patients in both cohorts once each is generated on
  Eunomia.

Pythia can fetch library phenotypes itself, which would make that comparison
circular. So there are two kinds of library scenario:

- **blind**: the request rules the library out. The trace must show no
  library or saved-concept-set tool was used (otherwise the test fails), and
  the agent's own cohort is scored against the hidden reference.
- **reuse**: the request invites a library phenotype. The agent must fetch one,
  and the saved cohort must match the one it fetched exactly, with person
  Jaccard of at least 0.95.

`checks.selftest.e2e.ts` tests the checks themselves: each one gets a good
input and a bad one. It needs no model, takes about 10 seconds, and runs first.

## Running

```bash
npm ci
npm run e2e:assets            # build the plugin into frontend-assets/
docker compose up -d --wait   # needs AWS_BEARER_TOKEN_BEDROCK in .env
npx playwright install chromium
npm run test:e2e              # all; or: npm run test:e2e -- -g blind
```

Each agent scenario takes several minutes of model time, and there are seven.
The test cohorts and reference cohorts are deleted afterwards unless you set
`PYTHIA_E2E_KEEP=1`. The report has these attachments for each scenario:

- the tool-call list (`tool-calls.txt`)
- the full trace (`trace.json`)
- the transcript
- each saved cohort definition
- annotations with the concept and person scores

Environment variables: `PYTHIA_E2E_ATLAS_URL` (default `https://localhost`),
`PYTHIA_E2E_WEBAPI_URL` (default `http://localhost:8080/WebAPI`),
`PYTHIA_E2E_SOURCE_KEY` (default: the first CDM source),
`PYTHIA_E2E_COMPOSE_PROJECT` (default `atlas3-trex`; the person-level check
reads the results table through `docker compose exec`, and is skipped with a
note when that fails), and `ATLAS_USER` / `ATLAS_PASS` (default `admin` /
`admin`).

CI runs the suite nightly in the `cohort-e2e` job of
`.github/workflows/agent-evals.yml`.
