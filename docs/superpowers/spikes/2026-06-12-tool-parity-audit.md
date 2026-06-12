# Tool parity audit — CLJS agent vs JVM `tool-specs`

Date: 2026-06-12
Branch: `p-hoffmann/pythia-cljs-agent`

## Goal

The CLJS agent exposes tools to Bedrock. Tool `:name`s are a frontend contract
(the `*ProposalCard.vue` components dispatch on them). The exposed CLJS
tool-name set MUST be byte-identical to the JVM source of truth:
`../trex/plugins/bao/java/src/trexsql/agent/tools.clj` → `tool-specs`.

## Source of truth — JVM `tool-specs`

40 tools total: 19 `:side :server`, 21 `:side :client`.

### Server (`:side :server`, 19)

```
search_existing_cohorts            search_existing_concept_sets
search_existing_feature_analyses   search_existing_characterizations
search_existing_pathways           search_existing_incidence_rates
search_phenotypes                  get_reference_phenotype
validate_circe                     search_ohdsi_book
web_search                         get_artifact
search_concepts                    draft_concept_set_spec
verify_concept_mapping             get_cohort_generation_summary
summarise_attrition                get_cohort_overlap
search_ohdsi_studies
```

### Client (`:side :client`, 21)

```
add_criterion              add_criteria              set_entry_event
set_observation_window     add_exit_criterion        set_censor_event
create_standalone_concept_set                        navigate_to
add_inclusion_rule         create_feature_analysis   create_characterization
create_pathway             update_concept_set        update_feature_analysis
update_characterization    update_pathway            update_incidence_rate
create_incidence_rate      ask_user                  create_plan
update_plan_step
```

## CLJS exposure

- Server tools: `pythia.tools/server` (19 maps, each with `:run`).
- Client tools: `pythia.tools.client/client-tools` (21 maps, schema-only, NO `:run`).
- `pythia.tools/all` = `(into server client-tools)` → 40 tools.
- `registry/tools-object` builds an SDK `tool(...)` WITHOUT `:execute` for any
  map lacking `:run` (verified via the `ai` package: a `tool()` built without
  `execute` has no `execute` key — `'execute' in t === false`).

## Result

**JVM count: 40. CLJS count: 40. Name sets are IDENTICAL.**

`pythia.tools.parity-test` asserts set equality both directions
(`set/difference` empty each way), 40-count, uniqueness, 21 client tools,
client SDK tools have NO `execute`, server SDK tools DO have `execute`.
All 89 tests / 318 assertions pass.

### Name mismatches found + fixed

None. The earlier-ported server tools already carry the exact JVM `:name`.
Specifically the known risk was clear: the file `search_concept_sets.cljs`
defines `:name "search_existing_concept_sets"` (not `search_concept_sets`),
matching the JVM. Other ns-derived-looking names (`search_existing_*`) are all
correct `:name` strings.

### Schema spot-check (3+ tools vs JVM `:input-schema`)

- `search_concepts` — matches verbatim (`{query, domain(enum=domain-enum)}`, required `[query]`).
- `search_existing_cohorts` — matches verbatim.
- `search_existing_concept_sets` — matches verbatim (`{query, limit}`, required `[query]`).
- `get_artifact` — matches verbatim (`kind` enum of 6, `id`, required `[kind id]`).
- `validate_circe` — **DRIFT FOUND + FIXED**: CLJS had
  `:expression {:type "object" :description "Circe CohortExpression JSON ..."}`;
  JVM declares `:type ["string" "object"]` with the longer description
  ("either a JSON string or the equivalent JSON object. Must contain at minimum
  a PrimaryCriteria.CriteriaList."). Updated `validate_circe.cljs` `schema` to
  match the JVM exactly. (The `coerce-expression` run logic already accepted
  both string and object, so only the advertised schema was wrong.)

## Notes

- `navigate_to` `view` enum: JVM computes it dynamically from
  `routes-manifest/agent-visible-views`. CLJS embeds the same 31 agent-visible
  view names (manifest order) in `pythia.routes/agent-visible-views`, sourced
  from `src/routes.manifest.json` (`agentVisible=true` entries). Regenerate this
  vector if the manifest changes.

---

# Fidelity audit — prompt + full per-tool description/schema (2026-06-12, pass 2)

A prior porting pass had TRUNCATED the system prompt and PARAPHRASED several
tool descriptions/schemas. This pass restored byte-fidelity against the JVM
source at `../trex` (branch `p-hoffmann/agent-proxy-cutover`, commit `HEAD~1`).

## Prompt restoration (`agent/src/pythia/prompt.cljc`)

The truncated `base-prompt` carried only 5 sections; the JVM static
`system-prompt` has 16. Restored verbatim.

**Method**: rendered the JVM `(str "..." (format-views) "...")` (resolving
`format-views` over the manifest's agentVisible entries) and the CLJS
`base-prompt`, then byte-compared.

**Result: IDENTICAL — both 27500 chars, exact byte match.**

Sections restored (11 previously missing, all 16 now present):
- `## Phenotype Design Workflow` (expanded from 5 truncated steps to the full
  6-step flow: search_existing_cohorts matchScore gate, search_phenotypes +
  circe-summary, full phenotype design, draft_concept_set_spec two-stage
  pattern, search_concepts + verify_concept_mapping, add_criteria batch)
- `## OHDSI Conventions` (added the search_ohdsi_book methodology bullet)
- `## Diagnostic Interpretation` (NEW — generation summary / attrition /
  overlap / search_ohdsi_studies)
- `## Limited Vocabulary Fallback` (NEW — Eunomia well-known concept IDs)
- `## Rules` (expanded from 8 paraphrased rules to the full 12, incl. the
  validate_circe rule #11 and the STOP-after-proposing rule #12 with its
  create_plan/ask_user exceptions)
- `## Plans` (NEW — create_plan / update_plan_step authoring)
- `## Web research` (NEW — web_search good uses / anti-patterns)
- `## Visual style` (expanded to the full MDI-shortcode guidance)
- `## ATLAS v3.0 cohort model (Phase B tools)` (NEW)
- `## Asking the user` (NEW — ask_user triggers / anti-patterns)
- `## Current screen awareness — read before you edit` (NEW)
- `## Where the user is, and how to navigate` (NEW — interpolates
  `format-views`)
- `## Analysis types — feature analyses, characterizations, pathways,
  incidence rates` (NEW)
- `### Characterization prerequisite branch (IMPORTANT)` (NEW)

`format-views` reproduced in `prompt.cljc` over a new
`pythia.routes/agent-visible-entries` (full `{:name :params :label}` per
manifest entry; `agent-visible-views` is now derived as `(mapv :name ...)`),
mirroring the JVM `rm/agent-visible-entries`. Output e.g.
`- \`cohort-edit\` — params: id — Cohort editor`.

**Static/dynamic combination**: the JVM `routes.clj` passes the static
`prompt/system-prompt` and the `rcf/format-route-context` dynamic context as
SEPARATE args to `bedrock/converse-stream`. The CLJS architecture
(`entry.cljs` → `sdk/stream-chat`) takes a single `system` string, so
`prompt/system-prompt` concatenates `base-prompt` + a dynamic
`## Current context` block (the existing `context-block`, keyed off the CLJS
request body's `{:route :artifact{:kind :id :name}}`). Net effect matches the
JVM: both deliver static prose + dynamic context to the model's system prompt.

Prompt tests added: `system-prompt-restored-sections-present` asserts all 14
`##`/`###` headers + two `format-views` bullets; the empty-context test now
asserts the dynamic block's distinctive prose ("The user is currently here.")
is absent rather than the literal "Current context" header (which the static
prose legitimately mentions in the screen-awareness section).

## Per-tool description + schema audit (all 40)

**Method**: parsed JVM `tool-specs` (resolving shared schema vars
`criterion-schema`, `concept-ref-schema`, `temporal-window-schema`,
`domain-enum`, `operator-enum`, `(rm/agent-visible-views)`,
`(assoc concept-ref-schema ...)`) into JSON; dumped the 40 CLJS tool maps
(`:name/:description/:schema`) to JSON; deep-compared description (exact
string) + input-schema (structural deep-equality).

**5 tools had description drift, 4 had schema drift (35/40 already clean):**

| tool | description | schema |
|------|-------------|--------|
| `get_reference_phenotype` | paraphrased → restored | `cohortId` was missing its `:description` → added |
| `search_ohdsi_book` | paraphrased → restored | `query` missing `:description`; `k` description "(1-10, default 3)" → JVM "(default 3, max 10)" |
| `search_phenotypes` | paraphrased (dropped Phenotype-Library/circe-summary mention) → restored | `query` missing `:description` → added |
| `validate_circe` | paraphrased → restored (schema was already correct) | — |
| `web_search` | paraphrased → restored | `query` + `num_results` both missing `:description` → added |

All fixes touched `:description`/`:schema` only; `:run` logic untouched.
Re-ran the deep comparison after fixes: **0 description drift, 0 schema drift —
ALL CLEAN.**

## Verify

- `npx shadow-cljs compile test && node out/test.cjs` → 90 tests, 334
  assertions, 0 failures.
- `npx shadow-cljs release fn` clean; `npm run sync` copied `handler.js` +
  resources into `plugin/functions/`.
